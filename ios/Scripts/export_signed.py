#!/usr/bin/env python3
"""兼容入口：调用通用打包后端；只使用钥匙串已有身份，不接收密码。"""
import argparse
from pathlib import Path
import os
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--profile',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--config',type=Path,default=root/'Packager/游戏29示例_game29.json')
    p.add_argument('--identity')
    a = p.parse_args()
    args = [sys.executable,str(root/'Packager/Backend/build.py'),'build','--source',str(root),
            '--config',str(a.config),'--profile',str(a.profile),'--output',str(a.output)]
    if a.identity: args += ['--identity',a.identity]
    os.execv(sys.executable,args)


if __name__ == '__main__': main()
