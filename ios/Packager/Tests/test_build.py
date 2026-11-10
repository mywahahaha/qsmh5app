import copy
import importlib.util
import json
from pathlib import Path
import plistlib
import shutil
import struct
import sys
import tempfile
import unittest
import zlib
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'Packager/Backend'))
import build


def png(path,width=1024,height=1024,alpha=255):
    def chunk(kind,data):
        return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data))
    pixels = (b'\0'+bytes([30,60,90,alpha])*width)*height
    path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,6,0,0,0))+
                     chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b''))


class BuildTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(); cls.root = Path(cls.temp.name)
        cls.helper = build.compile_images(cls.root,build.developer_environment())
        cls.icon = cls.root/'icon.png'; png(cls.icon)
        cls.c = dict(game_id=29,display_name='中文游戏 & 测试',bundle_id='com.test.game29',version='1.2.3',build='8',
                     orientation='landscape',icon_path=str(cls.icon),splash_dir='',game_url='',channel_code='',convert_icon=False)
    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()
    def test_invalid_identity_and_secret_input_rejected(self):
        for changes in [dict(game_id=True),dict(game_id=0),dict(bundle_id='a.*'),dict(orientation='other'),
                        dict(build='0'),dict(game_url='http://example.test'),dict(game_url='https://u:p@example.test'),
                        dict(channel_code='../x'),dict(signing_password='never-save')]:
            with self.subTest(changes=changes),self.assertRaises(ValueError): build.validate(dict(self.c,**changes))
    def test_real_png_validation_and_explicit_legacy_icon_conversion(self):
        icon, images = build.assets(self.c,self.helper)
        self.assertEqual(icon['width'],1024); self.assertTrue(icon['opaque']); self.assertEqual(images,[])
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp)/'legacy.png'; png(p,512,512,alpha=100)
            c = dict(self.c,icon_path=str(p))
            with self.assertRaises(ValueError): build.assets(c,self.helper)
            info,_ = build.assets(dict(c,convert_icon=True),self.helper)
            self.assertFalse(info['opaque'])
            out=Path(temp)/'AppIcon.appiconset'; build.run([self.helper,'icon-compatible',p,out])
            final=build.image_info(self.helper,out/'icon-1024-1.png'); self.assertTrue(final['opaque']);self.assertEqual(final['width'],1024)
    def test_filename_pixels_and_duplicate_dimensions(self):
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp); png(folder/'a-100x200.png',100,200); png(folder/'b-100x200.png',100,200)
            _,mapped=build.assets(dict(self.c,splash_dir=temp),self.helper)
            self.assertEqual(len(mapped),1);self.assertTrue(mapped[0]['source'].endswith('a-100x200.png'))
            png(folder/'bad-200x100.png',100,200)
            with self.assertRaises(ValueError): build.assets(dict(self.c,splash_dir=temp),self.helper)
    def test_corrupted_png_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'broken.png'; p.write_bytes(b'\x89PNG\r\n\x1a\ninvalid')
            with self.assertRaises(Exception): build.image_info(self.helper,p)
    def test_two_staged_games_do_not_reuse_url_channel_or_orientation(self):
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp); source=folder/'source'; (source/'Resources').mkdir(parents=True);(source/'Config').mkdir()
            (source/'Config/Info.plist').write_bytes(plistlib.dumps({'QSMInitialGameURL':'https://old.test/game29'}))
            (source/'Resources/packCode/old').mkdir(parents=True)
            (source/'Config/LocalOverrides.xcconfig').write_text('should never copy')
            icon,_=build.assets(self.c,self.helper)
            for n,c in enumerate([dict(self.c,channel_code='1-218'),dict(self.c,game_id=30,bundle_id='com.test.game30',orientation='portrait')]):
                target=folder/str(n);manifest=build.stage(source,target,c,self.helper,[],icon)
                info=plistlib.loads((target/'Config/Info.plist').read_bytes())
                self.assertEqual(info['QSMGameID'],str(c['game_id']));self.assertEqual(info['QSMInitialGameURL'],'')
                self.assertEqual(info['UISupportedInterfaceOrientations'],build.DIRECTIONS[c['orientation']])
                self.assertEqual(info['QSMInitialScreenMode'],build.SCREEN_MODES[c['orientation']])
                self.assertFalse((target/'Config/LocalOverrides.xcconfig').exists())
                if n == 0:
                    self.assertEqual([p.name for p in (target/'Resources/packCode').iterdir()],['1-218'])
                else:
                    self.assertFalse((target/'Resources/packCode').exists())
                self.assertNotIn('icon_path',manifest)
    def test_profile_mismatch_rejected_before_identity_lookup(self):
        import datetime
        p={'ExpirationDate':datetime.datetime(2099,1,1),'TeamIdentifier':['TEAM'],
           'Entitlements':{'application-identifier':'TEAM.com.allowed.*'}}
        with patch.object(build,'decode_profile',return_value=p),self.assertRaises(ValueError):
            build.inspect_profile('unused','com.other.game')
    def test_profile_path_must_be_a_mobileprovision_file(self):
        with tempfile.TemporaryDirectory() as folder:
            for path in ['', folder, str(Path(folder)/'missing.mobileprovision')]:
                with self.subTest(path=path),patch.object(build,'capture') as capture,self.assertRaisesRegex(ValueError,'请选择有效的'):
                    build.decode_profile(path)
                capture.assert_not_called()
    def test_profile_requires_matching_private_key(self):
        import datetime
        p={'ExpirationDate':datetime.datetime(2099,1,1),'TeamIdentifier':['TEAM'],'Name':'test',
           'UUID':'11111111-1111-1111-1111-111111111111','ProvisionedDevices':['device'],
           'DeveloperCertificates':[b'certificate'],
           'Entitlements':{'application-identifier':'TEAM.com.allowed.*','get-task-allow':False}}
        with patch.object(build,'decode_profile',return_value=p),patch.object(build,'identities',return_value=[]),self.assertRaises(ValueError):
            build.inspect_profile('unused','com.allowed.game')
    def test_output_collision_stops_before_signing_or_building(self):
        import argparse
        with tempfile.TemporaryDirectory() as temp:
            config=Path(temp)/'config.json';config.write_text(json.dumps(self.c))
            args=argparse.Namespace(source=ROOT,config=config,output=Path(temp),unsigned=True)
            with patch.object(build,'compile_images') as compile_image,self.assertRaises(ValueError): build.build(args)
            compile_image.assert_not_called()


if __name__=='__main__':unittest.main()
