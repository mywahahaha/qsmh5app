#!/usr/bin/env python3
"""iOS 多游戏打包后端：隔离构建、手动签名与最终 IPA 校验。密码只由原生界面处理。"""
import argparse
import datetime as dt
import fcntl
import fnmatch
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import zipfile
from urllib.parse import urlsplit

if sys.version_info < (3, 11):
    raise SystemExit("需要 Python 3.11+；请使用 ios/Packager/运行打包器_package.sh 或在界面选择正确解释器")

BACKEND = Path(__file__).resolve().parent
DIRECTIONS = {
    'landscape': ['UIInterfaceOrientationLandscapeLeft', 'UIInterfaceOrientationLandscapeRight',
                  'UIInterfaceOrientationPortrait'],
    'portrait': ['UIInterfaceOrientationPortrait', 'UIInterfaceOrientationLandscapeLeft',
                 'UIInterfaceOrientationLandscapeRight'],
}
SCREEN_MODES = {'landscape': 'horizontal', 'portrait': 'vertical'}


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def capture(args, **kwargs):
    return run(args, capture_output=True, **kwargs).stdout


def read_json(path):
    return json.loads(Path(path).read_text())


def save_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def developer_environment():
    candidates = [os.environ.get('DEVELOPER_DIR', ''), '/Applications/Xcode.app/Contents/Developer',
                  '/Volumes/SSD/Applications/Xcode.app/Contents/Developer']
    try:
        candidates.insert(1, capture(['xcode-select', '-p']).decode().strip())
    except subprocess.CalledProcessError:
        pass
    for value in candidates:
        if value and (Path(value) / 'Platforms/iPhoneOS.platform').is_dir():
            return dict(os.environ, DEVELOPER_DIR=value)
    raise ValueError('找不到完整 Xcode，请安装或设置 DEVELOPER_DIR')


def validate(config):
    c = dict(config)
    if type(c.get('game_id')) is not int or c['game_id'] <= 0:
        raise ValueError('游戏 ID 必须是正整数')
    for field in ['display_name', 'bundle_id', 'version', 'build', 'orientation', 'icon_path']:
        if not isinstance(c.get(field), str) or not c[field].strip() or any(ord(x) < 32 for x in c[field]):
            raise ValueError('字段缺失或含控制字符：' + field)
    if len(c['display_name']) > 60 or '$' in c['display_name']:
        raise ValueError('游戏名称过长或包含构建变量字符')
    if not re.fullmatch(r'[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+', c['bundle_id']):
        raise ValueError('Bundle ID 格式错误')
    if not re.fullmatch(r'\d+\.\d+\.\d+', c['version']):
        raise ValueError('版本需要为三段数字，例如 1.0.1')
    if not re.fullmatch(r'[1-9]\d{0,8}', c['build']):
        raise ValueError('首版构建号使用正整数，最多 9 位')
    if c['orientation'] not in DIRECTIONS:
        raise ValueError('方向只能为 landscape 或 portrait')
    for field in ['game_url','splash_dir']:
        if not isinstance(c.get(field,''),str): raise ValueError(field+' 必须是字符串')
    url = c.get('game_url', '').strip()
    if url:
        parts = urlsplit(url)
        if parts.scheme != 'https' or not parts.hostname or parts.username or parts.password or '$' in url:
            raise ValueError('游戏地址覆盖必须为无账号密码的 HTTPS 地址')
    c['game_url'] = url
    channel = c.get('channel_code', '')
    if not isinstance(channel, str) or (channel and not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', channel)):
        raise ValueError('渠道码仅支持 1–100 位字母、数字、横线和下划线')
    if type(c.get('convert_icon', False)) is not bool:
        raise ValueError('convert_icon 必须是布尔值')
    forbidden = {'password', 'signing_password', 'p12_password', 'private_key'}
    if any(k.lower() in forbidden for k in c):
        raise ValueError('配置不允许保存密码或私钥，请使用原生安全输入')
    return c


def identities():
    raw = capture(['security', 'find-identity', '-v', '-p', 'codesigning']).decode()
    return [{'sha1': m[0], 'name': m[1]} for m in re.findall(r'\b([0-9A-F]{40}) "([^"]+)"', raw)]


def decode_profile(path):
    profile = Path(path)
    if not profile.is_file() or profile.suffix.lower() != '.mobileprovision':
        raise ValueError('请选择有效的 .mobileprovision 描述文件：' + str(profile))
    return plistlib.loads(capture(['security', 'cms', '-D', '-i', profile.resolve()]))


def inspect_profile(path, bundle=None, identity=None):
    p = decode_profile(path)
    if p['ExpirationDate'] <= dt.datetime.now(dt.timezone.utc).replace(tzinfo=None):
        raise ValueError('描述文件已过期')
    team = p['TeamIdentifier'][0]
    e = p['Entitlements']
    if bundle and not fnmatch.fnmatchcase(team + '.' + bundle, e.get('application-identifier', '')):
        raise ValueError('描述文件不支持 Bundle ID：' + bundle)
    if not re.fullmatch(r'[A-Fa-f0-9-]{36}', p['UUID']):
        raise ValueError('描述文件 UUID 无效')
    if p.get('ProvisionsAllDevices'):
        method, kind = 'enterprise', 'Enterprise'
    elif p.get('ProvisionedDevices'):
        method, kind = ('debugging', 'Development') if e.get('get-task-allow') else ('release-testing', 'AdHoc')
    else:
        raise ValueError('首版仅支持设备分发描述文件，不执行 App Store 上传')
    allowed = {hashlib.sha1(c).hexdigest().upper() for c in p['DeveloperCertificates']}
    available = [i for i in identities() if i['sha1'] in allowed and (not identity or i['sha1'] == identity)]
    if not available:
        raise ValueError('钥匙串没有匹配的有效证书与私钥；请导入匹配身份或重新选择')
    return {'uuid': p['UUID'], 'name': p['Name'], 'team': team, 'app_id': e['application-identifier'],
            'expires_utc': p['ExpirationDate'].isoformat() + 'Z', 'device_count': len(p.get('ProvisionedDevices', [])),
            'method': method, 'kind': kind, 'identity': available[0]['sha1']}


def compile_images(folder, env):
    exe = folder / 'images'
    run(['xcrun', '--sdk', 'macosx', 'swiftc', BACKEND / 'images.swift', '-o', exe], env=env)
    return exe


def image_info(helper, path):
    path = Path(path)
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 16_000_000:
        raise ValueError('图片不存在、为链接或超过 16MB：' + str(path))
    return json.loads(capture([helper, 'inspect', path]))


def assets(config, helper):
    icon = image_info(helper, config['icon_path'])
    if icon['width'] != icon['height']:
        raise ValueError('游戏图标必须为正方形')
    if not config.get('convert_icon') and (icon['width'] != 1024 or not icon['opaque']):
        raise ValueError('需要 1024×1024 不透明 PNG；旧素材可显式启用图标兼容转换')
    mapped = []
    seen = set()
    folder = config.get('splash_dir', '')
    if folder:
        folder = Path(folder)
        if not folder.is_dir() or folder.is_symlink():
            raise ValueError('闪图目录不存在或为链接')
        for path in sorted(folder.iterdir()):
            if path.is_dir():
                continue
            size = re.search(r'(?<!\d)(\d{3,5})x(\d{3,5})(?!\d)', path.name, re.I)
            if path.suffix.lower() != '.png' or not size:
                raise ValueError('闪图必须为 PNG 且文件名包含宽x高：' + path.name)
            info = image_info(helper, path)
            w, h = map(int, size.groups())
            if (w, h) != (info['width'], info['height']) or w == h:
                raise ValueError('闪图实际尺寸与命名不符或为方图：' + path.name)
            if (w, h) in seen:
                print('提示：跳过重复闪图尺寸 ' + path.name, flush=True)
                continue
            seen.add((w, h))
            mapped.append({'source': str(path.resolve()), 'file': f'splash_{w}x{h}.png', 'width': w, 'height': h})
    return icon, mapped


def stage(source, dest, c, helper, mapped, icon):
    # SDK 安装目录参与构建；构建产物、用户签名覆盖与工具自身不复制。
    def ignore(folder, names):
        ignored = {n for n in names if n in {'artifacts', 'DerivedData', '.build', 'Packager', 'xcuserdata', '__pycache__'}
                   or n.endswith(('.p12', '.pfx', '.cer', '.mobileprovision')) or n == 'LocalOverrides.xcconfig'}
        return ignored
    shutil.copytree(source, dest, ignore=ignore, symlinks=True)
    res = dest / 'Resources'
    splash = res / 'StartupImages'; shutil.rmtree(splash, ignore_errors=True); splash.mkdir()
    for item in mapped:
        shutil.copyfile(item['source'], splash / item['file'])
    save_json(res / 'startup-splashes.json', [{k: v for k, v in a.items() if k != 'source'} for a in mapped])
    icon_dest = res / 'Assets.xcassets/AppIcon.appiconset'
    shutil.rmtree(icon_dest, ignore_errors=True)
    run([helper, 'icon-compatible' if c.get('convert_icon') else 'icon', c['icon_path'], icon_dest])
    channel = res / 'packCode'; shutil.rmtree(channel, ignore_errors=True)
    if c.get('channel_code'):
        (channel / c['channel_code']).mkdir(parents=True)
    # 不经过 xcconfig 字符串插值写用户内容，避免 URL、中文名称和特殊字符被截断。
    info_path = dest / 'Config/Info.plist'
    info = plistlib.loads(info_path.read_bytes())
    info.update(CFBundleDisplayName=c['display_name'], QSMGameID=str(c['game_id']), QSMInitialGameURL=c['game_url'],
                QSMInitialScreenMode=SCREEN_MODES[c['orientation']],
                UISupportedInterfaceOrientations=DIRECTIONS[c['orientation']])
    info_path.write_bytes(plistlib.dumps(info))
    public = {k: c.get(k, '') for k in ['game_id','display_name','bundle_id','version','build','orientation','game_url','channel_code']}
    public['iconConversion'] = bool(c.get('convert_icon')) and (icon['width'] != 1024 or not icon['opaque'])
    public['sdkRequired'] = (source/'Vendor/Aliyun/installed.json').is_file()
    public['sourceIconSHA256'] = sha(c['icon_path'])
    public['resources'] = {p.relative_to(res).as_posix(): sha(p) for p in sorted(res.rglob('*'))
                           if p.is_file() and p.name != 'packaging-manifest.json'}
    save_json(res / 'packaging-manifest.json', public)
    return public


def check_app(app, c, manifest=None, signing=None):
    info = plistlib.loads((app / 'Info.plist').read_bytes())
    expected = {'CFBundleIdentifier':c['bundle_id'], 'CFBundleShortVersionString':c['version'], 'CFBundleVersion':c['build'],
                'CFBundleDisplayName':c['display_name'], 'QSMGameID':str(c['game_id']), 'QSMInitialGameURL':c['game_url'],
                'QSMInitialScreenMode':SCREEN_MODES[c['orientation']]}
    for key, value in expected.items():
        if info.get(key) != value:
            raise ValueError('产物配置不一致：' + key)
    if set(info['UISupportedInterfaceOrientations']) != set(DIRECTIONS[c['orientation']]):
        raise ValueError('产物方向不一致')
    if 'NSAppTransportSecurity' in info:
        raise ValueError('生产包包含本地网络例外')
    binary = app / info['CFBundleExecutable']
    if any(x in binary.read_bytes() for x in [b'qsm-reset', b'fixture-h5-token', b'local-test-secret', b'localhost:18740']):
        raise ValueError('生产包混入本地测试入口')
    if list(app.rglob('page.html')) or any(p.suffix.lower() in {'.p12','.pfx','.cer'} for p in app.rglob('*')):
        raise ValueError('产物包含测试页面或证书资料')
    if not (app / 'Assets.car').is_file() or not info.get('CFBundleIcons', {}).get('CFBundlePrimaryIcon'):
        raise ValueError('正式 AppIcon 缺失')
    if manifest:
        if manifest.get('sdkRequired'):
            for name in ['ATAuthSDK.bundle','ATAuthSDK-Privacy.bundle/PrivacyInfo.xcprivacy',
                         'YTXMonitor-Privacy.bundle/PrivacyInfo.xcprivacy','YTXOperators-Privacy.bundle/PrivacyInfo.xcprivacy']:
                if not (app/name).exists(): raise ValueError('最终产物 SDK 资源缺失：'+name)
        if read_json(app / 'packaging-manifest.json') != manifest:
            raise ValueError('产物资源清单不一致')
        for name, expected_hash in manifest['resources'].items():
            if name.startswith('Assets.xcassets/'):
                continue  # 资产被 actool 编译；通过 AppIcon 元数据与 Assets.car 检查。
            target = app / name
            if not target.is_file() or sha(target) != expected_hash:
                raise ValueError('产物资源缺失或被修改：' + name)
    splashes = read_json(app / 'startup-splashes.json')
    for item in splashes:
        if not (app / 'StartupImages' / item['file']).is_file():
            raise ValueError('闪图清单资源缺失')
    if not c.get('channel_code') and (app / 'packCode').exists():
        raise ValueError('空渠道码产物不应包含 packCode 目录')
    directories = sorted(p.name for p in (app / 'packCode').iterdir() if p.is_dir()) if (app / 'packCode').exists() else []
    if directories != ([c['channel_code']] if c.get('channel_code') else []):
        raise ValueError('渠道码目录不一致')
    if signing:
        run(['codesign', '--verify', '--deep', '--strict', app], capture_output=True)
        embedded = decode_profile(app / 'embedded.mobileprovision')
        if embedded['UUID'] != signing['uuid']:
            raise ValueError('最终 IPA 描述文件不一致')
        ent = plistlib.loads(capture(['codesign', '-d', '--entitlements', ':-', app]))
        if ent.get('application-identifier') != signing['team'] + '.' + c['bundle_id'] or ent.get('com.apple.developer.team-identifier') != signing['team']:
            raise ValueError('最终 IPA 权限身份不一致')
        if bool(ent.get('get-task-allow')) != (signing['kind'] == 'Development'):
            raise ValueError('最终 IPA 调试权限不一致')
        with tempfile.TemporaryDirectory() as certdir:
            prefix = Path(certdir) / 'cert'
            run(['codesign', '-d', '--extract-certificates='+str(prefix), app], capture_output=True)
            if hashlib.sha1(Path(str(prefix)+'0').read_bytes()).hexdigest().upper() != signing['identity']:
                raise ValueError('最终 IPA 实际签名证书不一致')
    return {'executableSHA256':sha(binary), **expected}


def verify_ipa(ipa, folder, c, manifest, signing):
    # ditto 保留嵌套代码的符号链接和权限；先拒绝路径穿越条目。
    with zipfile.ZipFile(ipa) as z:
        for item in z.infolist():
            parts = Path(item.filename).parts
            if item.filename.startswith('/') or '..' in parts:
                raise ValueError('IPA 含非法路径')
    run(['ditto', '-x', '-k', ipa, folder])
    apps = list((folder / 'Payload').glob('*.app'))
    if len(apps) != 1:
        raise ValueError('IPA 必须包含一个主应用')
    return check_app(apps[0], c, manifest, signing)


def load_config(path, source):
    c = validate(read_json(path))
    for field in ['icon_path','splash_dir']:
        if c.get(field) and not Path(c[field]).is_absolute():
            c[field] = str((source.resolve().parent / c[field]).resolve())
    return c


def build(args):
    source = args.source.resolve()
    if not (source / 'QSMH5App.xcodeproj').is_dir():
        raise ValueError('--source 应指向仓库 ios/ 目录')
    c = load_config(args.config, source)
    env = developer_environment()
    output = args.output.resolve()
    if output.exists():
        raise ValueError('输出目录已存在，拒绝覆盖')
    output.parent.mkdir(parents=True, exist_ok=True)
    signing = None if args.unsigned else inspect_profile(args.profile, c['bundle_id'], args.identity)
    # 跨进程串行，避免描述文件临时安装相互覆盖。
    lock_dir = Path.home() / 'Library/Caches/QSMH5Packager'; lock_dir.mkdir(parents=True, exist_ok=True)
    with (lock_dir / 'build.lock').open('w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('已有出包任务运行，请等待其结束')
        with tempfile.TemporaryDirectory(prefix='.qsm-work-', dir=output.parent) as temp:
            work = Path(temp); result = work / 'result'; result.mkdir()
            installed = None; original = None
            try:
                print('1/5 校验资源与签名', flush=True)
                helper = compile_images(work, env)
                icon, mapped = assets(c, helper)
                print('2/5 生成隔离工程与游戏资源', flush=True)
                project = work / 'Project'
                manifest = stage(source, project, c, helper, mapped, icon)
                archive = result / 'QSMH5App.xcarchive'
                command = ['xcodebuild', '-project', project/'QSMH5App.xcodeproj', '-scheme', 'QSMH5App-Integration',
                    '-configuration', 'Release', '-destination', 'generic/platform=iOS', '-derivedDataPath', work/'DerivedData',
                    '-archivePath', archive, 'PRODUCT_BUNDLE_IDENTIFIER='+c['bundle_id'], 'MARKETING_VERSION='+c['version'],
                    'CURRENT_PROJECT_VERSION='+c['build'],
                    'EXCLUDED_SOURCE_FILE_NAMES=' + ('' if c.get('channel_code') else 'packCode')]
                if signing:
                    profile_dir = Path.home()/'Library/MobileDevice/Provisioning Profiles'; profile_dir.mkdir(parents=True, exist_ok=True)
                    installed = profile_dir/(signing['uuid']+'.mobileprovision')
                    original = installed.read_bytes() if installed.exists() else None
                    shutil.copyfile(args.profile, installed)
                    command += ['CODE_SIGN_STYLE=Manual','CODE_SIGNING_ALLOWED=YES','DEVELOPMENT_TEAM='+signing['team'],
                                'CODE_SIGN_IDENTITY='+signing['identity'],'PROVISIONING_PROFILE_SPECIFIER='+signing['uuid']]
                else:
                    command += ['CODE_SIGNING_ALLOWED=NO']
                print('3/5 归档（详细日志：'+str(result/'archive.log')+'）', flush=True)
                with (result/'archive.log').open('w') as log:
                    run(command+['archive'], env=env, stdout=log, stderr=subprocess.STDOUT)
                apps = list((archive/'Products/Applications').glob('*.app'))
                if len(apps) != 1:
                    raise ValueError('归档必须包含一个应用')
                check_app(apps[0], c, manifest, signing)
                print('4/5 导出与验证最终 IPA', flush=True)
                export = work/'export'
                if signing:
                    options = work/'ExportOptions.plist'
                    options.write_bytes(plistlib.dumps({'method':signing['method'],'signingStyle':'manual','teamID':signing['team'],
                        'signingCertificate':signing['identity'],'provisioningProfiles':{c['bundle_id']:signing['uuid']},
                        'manageAppVersionAndBuildNumber':False}))
                    with (result/'export.log').open('w') as log:
                        run(['xcodebuild','-exportArchive','-archivePath',archive,'-exportOptionsPlist',options,'-exportPath',export],
                            env=env, stdout=log, stderr=subprocess.STDOUT)
                    ipas = list(export.glob('*.ipa'))
                    if len(ipas) != 1: raise ValueError('导出结果必须恰好包含一个 IPA')
                    ipa = ipas[0]
                else:
                    payload = work/'unsigned/Payload'; payload.mkdir(parents=True)
                    run(['ditto',apps[0],payload/apps[0].name])
                    ipa = work/'unsigned.ipa'
                    run(['ditto','-c','-k','--keepParent',payload,ipa])
                if (source/'Vendor/Aliyun/installed.json').exists():
                    for item in ['ATAuthSDK.bundle','ATAuthSDK-Privacy.bundle/PrivacyInfo.xcprivacy',
                                 'YTXMonitor-Privacy.bundle/PrivacyInfo.xcprivacy','YTXOperators-Privacy.bundle/PrivacyInfo.xcprivacy']:
                        if not (apps[0]/item).exists(): raise ValueError('SDK 资源缺失：'+item)
                verified = verify_ipa(ipa,work/'verify',c,manifest,signing)
                kind = signing['kind'] if signing else '未签名不可安装'
                name = f"{c['game_id']}_{c['bundle_id']}_{c['version']}_{c['build']}_{kind}.ipa"
                shutil.copyfile(ipa,result/name)
                revision = capture(['git','-C',source,'rev-parse','HEAD']).decode().strip()
                summary = {'configuration':manifest,'signing':signing,'verification':verified,'gitBase':revision,
                           'includesWorkingTree':True,'ipaSHA256':sha(result/name),'createdUTC':dt.datetime.now(dt.timezone.utc).isoformat()}
                save_json(result/'出包摘要.json',summary)
                (result/'SHA256.txt').write_text(summary['ipaSHA256']+'  '+name+'\n')
                (result/'安装说明.txt').write_text('分发类型：'+kind+'\n'+('仅限描述文件授权的设备与分发范围。' if signing else '未签名，不能直接安装。')+
                    '\n已验证最终 IPA；未执行真机安装、线上游戏或真实取号验收。\n')
                print('5/5 发布验证通过的产物', flush=True)
                os.rename(result,output)
                print('出包成功：'+str(output/name),flush=True)
            except BaseException:
                failure = Path(tempfile.mkdtemp(prefix='失败日志_',dir=output.parent))
                for log in result.glob('*.log'): shutil.copyfile(log,failure/log.name)
                print('任务未完成；日志：'+str(failure),file=sys.stderr,flush=True)
                raise
            finally:
                if installed:
                    if original is None: installed.unlink(missing_ok=True)
                    else: installed.write_bytes(original)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='command',required=True)
    sub.add_parser('identities')
    inspect = sub.add_parser('inspect-profile'); inspect.add_argument('--profile',type=Path,required=True)
    inspect.add_argument('--bundle'); inspect.add_argument('--identity')
    for name in ['check','build']:
        cmd = sub.add_parser(name); cmd.add_argument('--config',type=Path,required=True)
        cmd.add_argument('--source',type=Path,required=True); cmd.add_argument('--profile',type=Path)
        cmd.add_argument('--identity'); cmd.add_argument('--unsigned',action='store_true')
        if name == 'build': cmd.add_argument('--output',type=Path,required=True)
    a = p.parse_args()
    try:
        if a.command == 'identities': print(json.dumps(identities(),ensure_ascii=False)); return
        if a.command == 'inspect-profile': print(json.dumps(inspect_profile(a.profile,a.bundle,a.identity),ensure_ascii=False)); return
        if not a.unsigned and not a.profile: raise ValueError('签名出包必须选择描述文件')
        if a.command == 'check':
            c = load_config(a.config, a.source); env = developer_environment()
            if not (a.source/'QSMH5App.xcodeproj').is_dir(): raise ValueError('请选择正确的 ios 工程目录')
            signing = None if a.unsigned else inspect_profile(a.profile,c['bundle_id'],a.identity)
            with tempfile.TemporaryDirectory() as temp:
                helper = compile_images(Path(temp),env); icon, mapped = assets(c,helper)
            print(json.dumps({'configuration':'通过','icon':icon,'splashes':len(mapped),'signing':signing},ensure_ascii=False,indent=2))
        else: build(a)
    except (ValueError,OSError,KeyError,subprocess.CalledProcessError) as e:
        print('失败：'+str(e),file=sys.stderr); sys.exit(1)
    except KeyboardInterrupt:
        print('任务已取消',file=sys.stderr); sys.exit(130)


if __name__ == '__main__':
    if os.environ.get('QSM_PACKAGER_PROCESS_GROUP') == '1':
        try:
            os.setpgid(0, 0)
        except PermissionError:
            pass
    signal.signal(signal.SIGTERM,lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    main()
