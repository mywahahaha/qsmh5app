#!/usr/bin/env python3
"""按游戏配置验证归档；最终导出 IPA 的校验由通用打包后端执行。"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'Packager/Backend'))
from build import check_app, load_config


def channel_code(application_directory):
    try:
        apps = sorted(application_directory.iterdir(),key=lambda p:p.name)
    except OSError:
        return ''
    for app in apps:
        if not app.name.endswith('.app') or not app.is_dir() or app.is_symlink(): continue
        folder = app/'packCode'
        if not folder.is_dir() or folder.is_symlink(): continue
        try: children = sorted(folder.iterdir(),key=lambda p:p.name)
        except OSError: continue
        for child in children:
            if child.is_dir() and not child.is_symlink() and child.name.strip() and not any(unicodedata.category(c)=='Cc' for c in child.name):
                return child.name
    return ''


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('archive',type=Path)
    p.add_argument('--config',type=Path,default=ROOT/'Packager/游戏29示例_game29.json')
    a = p.parse_args()
    apps = list((a.archive/'Products/Applications').glob('*.app'))
    if len(apps)!=1: raise SystemExit('归档必须包含一个主应用')
    app = apps[0]; config = load_config(a.config,ROOT)
    manifest = json.loads((app/'packaging-manifest.json').read_text()) or None
    summary = check_app(app,config,manifest)
    signed = subprocess.run(['codesign','-d',str(app)],capture_output=True).returncode==0
    if signed: subprocess.run(['codesign','--verify','--deep','--strict',str(app)],check=True,capture_output=True)
    summary.update(channelCode=channel_code(app.parent),signing='归档已验签；最终 IPA 需独立验证' if signed else '未签名，不能安装')
    print(json.dumps(summary,ensure_ascii=False,indent=2))


if __name__ == '__main__': main()
