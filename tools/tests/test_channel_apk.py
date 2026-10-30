import struct
import tempfile
import unittest
from pathlib import Path

from tools.channel_apk import (
    CHANNEL_BLOCK_ID,
    ApkChannelError,
    build_signing_block,
    find_eocd,
    package_apk,
    read_signing_block,
)


def _pair(pair_id, value):
    return struct.pack("<Q", len(value) + 4) + struct.pack("<I", pair_id) + value


def _fake_signed_apk():
    prefix = b"fixture apk data"
    pairs = _pair(0x7109871A, b"signature-data") + _pair(0x12345678, b"other-data")
    signing_block = build_signing_block(pairs)
    central_directory = b"PK\x01\x02fake central directory"
    central_offset = len(prefix) + len(signing_block)
    eocd = bytearray(22)
    eocd[:4] = b"PK\x05\x06"
    struct.pack_into("<I", eocd, 12, len(central_directory))
    struct.pack_into("<I", eocd, 16, central_offset)
    return prefix + signing_block + central_directory + eocd


class ChannelApkTest(unittest.TestCase):
    def test_writes_and_replaces_channel_pair_while_preserving_other_pairs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "base.apk"
            output = root / "channel.apk"
            source.write_bytes(_fake_signed_apk())

            package_apk(source, output, "渠道-测试")
            with output.open("rb") as stream:
                eocd = find_eocd(stream)
                stream.seek(eocd + 16)
                central_offset = struct.unpack("<I", stream.read(4))[0]
                _, _, pairs = read_signing_block(stream, central_offset)

            self.assertIn(_pair(0x7109871A, b"signature-data"), pairs)
            self.assertIn(_pair(0x12345678, b"other-data"), pairs)
            self.assertIn(_pair(CHANNEL_BLOCK_ID, "渠道-测试".encode("utf-8")), pairs)
            original = source.read_bytes()
            package_apk(output, source.with_name("replaced.apk"), "new")
            self.assertEqual(original, source.read_bytes())

    def test_invalid_source_is_rejected_without_changing_existing_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "invalid.apk"
            output = root / "output.apk"
            source.write_bytes(b"not a signed apk")
            output.write_bytes(b"keep existing artifact")
            original_source = source.read_bytes()

            with self.assertRaises(ApkChannelError):
                package_apk(source, output, "channel")

            self.assertEqual(original_source, source.read_bytes())
            self.assertEqual(b"keep existing artifact", output.read_bytes())


if __name__ == "__main__":
    unittest.main()
