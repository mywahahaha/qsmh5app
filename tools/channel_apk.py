#!/usr/bin/env python3
"""在已签名 APK 的 APK Signing Block 中写入渠道码，无需重新签名。"""

from __future__ import annotations

import argparse
from datetime import datetime
import os
import struct
import sys
import tempfile
from pathlib import Path

CHANNEL_BLOCK_ID = 0x454D4348
MAGIC = b"APK Sig Block 42"
EOCD_SIGNATURE = b"PK\x05\x06"
EOCD_MIN_SIZE = 22
MAX_ZIP_COMMENT_SIZE = 0xFFFF


class ApkChannelError(Exception):
    pass


def log_failure(package_code: str, error: BaseException) -> None:
    """记录渠道分包失败详情；日志写入脚本所在目录，不影响原始错误返回。"""
    log_path = Path(__file__).resolve().with_name("error.log")
    timestamp = datetime.now().astimezone().strftime("%Y-%m-%d %H:%M:%S%z")
    # repr 可将换行、NUL 等不可见字符转义，确保每次失败固定占一条日志。
    entry = f"{timestamp} | packageCode={package_code!r} | 原因={error}\n"
    try:
        with log_path.open("a", encoding="utf-8") as log_file:
            log_file.write(entry)
    except (OSError, UnicodeError):
        # 日志目录权限异常不能掩盖实际的分包异常。
        pass


def read_exact(stream, size: int) -> bytes:
    value = stream.read(size)
    if len(value) != size:
        raise ApkChannelError("读取 APK 数据失败")
    return value


def find_eocd(stream) -> int:
    stream.seek(0, os.SEEK_END)
    file_size = stream.tell()
    search_size = min(file_size, EOCD_MIN_SIZE + MAX_ZIP_COMMENT_SIZE)
    if search_size < EOCD_MIN_SIZE:
        raise ApkChannelError("输入文件过小，不是有效 APK/ZIP")
    start = file_size - search_size
    stream.seek(start)
    tail = read_exact(stream, search_size)
    for index in range(search_size - EOCD_MIN_SIZE, -1, -1):
        if tail[index:index + 4] != EOCD_SIGNATURE:
            continue
        comment_length = struct.unpack_from("<H", tail, index + 20)[0]
        if index + EOCD_MIN_SIZE + comment_length == search_size:
            return start + index
    raise ApkChannelError("未找到 ZIP EOCD")


def read_signing_block(stream, central_directory_offset: int) -> tuple[int, int, bytes]:
    if central_directory_offset < 24:
        raise ApkChannelError("未找到 APK Signing Block，输入 APK 必须使用 v2/v3 签名")
    stream.seek(central_directory_offset - 24)
    footer = read_exact(stream, 24)
    block_size = struct.unpack_from("<Q", footer, 0)[0]
    if footer[8:] != MAGIC or block_size < 24:
        raise ApkChannelError("未找到 APK Signing Block，输入 APK 必须使用 v2/v3 签名")
    block_offset = central_directory_offset - block_size - 8
    if block_offset < 0:
        raise ApkChannelError("APK Signing Block 偏移非法")
    stream.seek(block_offset)
    block = read_exact(stream, block_size + 8)
    if struct.unpack_from("<Q", block, 0)[0] != block_size:
        raise ApkChannelError("APK Signing Block 前后长度不一致")
    return block_offset, len(block), block[8:-24]


def replace_channel_pair(pairs: bytes, channel: bytes) -> bytes:
    result = bytearray()
    offset = 0
    while offset < len(pairs):
        if len(pairs) - offset < 8:
            raise ApkChannelError("APK Signing Block ID-Value 对格式非法")
        pair_size = struct.unpack_from("<Q", pairs, offset)[0]
        if pair_size < 4 or pair_size > len(pairs) - offset - 8:
            raise ApkChannelError("APK Signing Block ID-Value 对长度非法")
        pair_end = offset + 8 + pair_size
        pair_id = struct.unpack_from("<I", pairs, offset + 8)[0]
        if pair_id != CHANNEL_BLOCK_ID:
            result.extend(pairs[offset:pair_end])
        offset = pair_end
    result.extend(struct.pack("<Q", 4 + len(channel)))
    result.extend(struct.pack("<I", CHANNEL_BLOCK_ID))
    result.extend(channel)
    return bytes(result)


def build_signing_block(pairs: bytes) -> bytes:
    block_size = len(pairs) + 24
    return struct.pack("<Q", block_size) + pairs + struct.pack("<Q", block_size) + MAGIC


def copy_range(source, target, offset: int, size: int) -> None:
    source.seek(offset)
    remaining = size
    while remaining:
        chunk = source.read(min(1024 * 1024, remaining))
        if not chunk:
            raise ApkChannelError("读取 APK 数据失败")
        target.write(chunk)
        remaining -= len(chunk)


def package_apk(input_path: Path, output_path: Path, package_code: str) -> None:
    if len(package_code.encode("utf-8")) > 4096 or "\x00" in package_code:
        raise ApkChannelError("packageCode 必须是不含 NUL 的 UTF-8 字符串，且不超过 4096 字节")
    if not input_path.is_file():
        raise ApkChannelError(f"输入 APK 不存在：{input_path}")
    if input_path.resolve() == output_path.resolve():
        raise ApkChannelError("输出 APK 必须与输入 APK 使用不同路径")
    output_path.parent.mkdir(parents=True, exist_ok=True)

    with input_path.open("rb") as source:
        eocd_offset = find_eocd(source)
        source.seek(eocd_offset + 16)
        central_directory_offset = struct.unpack("<I", read_exact(source, 4))[0]
        block_offset, block_length, pairs = read_signing_block(source, central_directory_offset)
        new_block = build_signing_block(replace_channel_pair(pairs, package_code.encode("utf-8")))
        new_central_directory_offset = central_directory_offset + len(new_block) - block_length
        if new_central_directory_offset > 0xFFFFFFFF:
            raise ApkChannelError("APK 偏移量超出 ZIP 格式支持范围")

        with tempfile.NamedTemporaryFile(dir=output_path.parent, prefix=output_path.name + ".", suffix=".tmp", delete=False) as temporary:
            temporary_path = Path(temporary.name)
            copy_range(source, temporary, 0, block_offset)
            temporary.write(new_block)
            copy_range(source, temporary, central_directory_offset, eocd_offset - central_directory_offset)
            source.seek(eocd_offset)
            eocd = bytearray(read_exact(source, EOCD_MIN_SIZE))
            comment_length = struct.unpack_from("<H", eocd, 20)[0]
            eocd.extend(read_exact(source, comment_length))
            struct.pack_into("<I", eocd, 16, new_central_directory_offset)
            temporary.write(eocd)
        os.replace(temporary_path, output_path)


def main() -> int:
    parser = argparse.ArgumentParser(description="为已签名 APK 写入渠道码")
    parser.add_argument("input_apk")
    parser.add_argument("output_apk")
    parser.add_argument("package_code", nargs="?", default="", help="渠道码；省略时写入空值")
    args = parser.parse_args()
    try:
        package_apk(Path(args.input_apk), Path(args.output_apk), args.package_code)
    except (ApkChannelError, OSError, UnicodeError) as error:
        log_failure(args.package_code, error)
        print(f"错误：{error}", file=sys.stderr)
        return 1
    print(f"渠道分包完成：{args.output_apk}")
    print(f"渠道码：{args.package_code}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
