#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""从 Pentax / RICOH 官方固件包提取工程模式入口文件名（8位.3位）。

用法：
    python 提取工程模式文件名.py k70_v116.zip
    python 提取工程模式文件名.py fwdc234b.bin

原理：
    官方固件是 LZ 帧压缩包，解压得到镜像。镜像尾部（文件偏移 0x12FFF00 附近，
    镜像副本 0x2FFF00）有 ROM 元数据记录，magic 为 "HOKKTKIYHTNTMU"，随后两个
    小端 u32 为 cpu 号与 public 号，再后 4 字节为 [次版本, 主版本, 0x0B, 0x14]。
    固件内文件名模板为 C:\%08ld.%03ld，因此文件名为 f"{public:08d}.{cpu:03d}"。
本工具只做本地解码，不运行固件、不访问相机。仅适用于带该记录格式的机型
（K-1、K-1 II、K-3 III、KF、K-70、KP 等）；K33 早期镜像格式不同。
"""
import io, struct, sys, zipfile, hashlib
from pathlib import Path

MAGIC = b"HOKKTKIYHTNTMU"


def unpack(data, start=0x200):
    pos, output = start, bytearray()
    while True:
        prefix = int.from_bytes(data[pos:pos + 2], "big")
        frame_offset, pos = pos, pos + 2
        if prefix == 0:
            return bytes(output)
        size = prefix & 0x7FFF
        end = pos + size
        assert end <= len(data), f"帧越界 @{frame_offset:x}"
        if prefix & 0x8000:
            output.extend(data[pos:end]); pos = end; continue
        ended = False
        while pos < end:
            bitmap = int.from_bytes(data[pos:pos + 2], "big"); pos += 2
            for bit in range(15, -1, -1):
                assert pos < end, f"截断 @{pos:x}"
                if bitmap & (1 << bit):
                    a, b = data[pos], data[pos + 1]; pos += 2
                    distance = ((a & 0xF8) << 5) | b
                    length = (a & 7) + 3
                    if length == 10:
                        while True:
                            extra = data[pos]; pos += 1
                            length += extra
                            if extra != 255:
                                break
                    if distance == 0:
                        ended = True; break
                    assert distance <= len(output), "回引越界"
                    for _ in range(length):
                        output.append(output[-distance])
                else:
                    output.append(data[pos]); pos += 1
            if ended:
                break
        assert ended, f"缺少结束标记 @{frame_offset:x}"


def load(path):
    raw = path.read_bytes()
    if path.suffix.lower() == ".zip":
        with zipfile.ZipFile(io.BytesIO(raw)) as z:
            names = [n for n in z.namelist() if n.lower().endswith(".bin")]
            assert names, "压缩包里没有 .bin"
            raw = z.read(names[0])
    return raw


def main():
    if len(sys.argv) != 2:
        print(__doc__); return 1
    path = Path(sys.argv[1])
    image = unpack(load(path))
    print(f"镜像：{len(image):,} 字节  sha256={hashlib.sha256(image).hexdigest()}")
    found = 0
    i = 0
    while True:
        i = image.find(MAGIC, i)
        if i < 0:
            break
        cpu, public = struct.unpack_from("<II", image, i + 16)
        minor, major = image[i + 28], image[i + 29]
        print(f"  记录 @0x{i:08X}: 固件 v{major}.{minor:02d}  cpu={cpu}  public={public}"
              f"  =>  {public:08d}.{cpu:03d}")
        found += 1
        i += 1
    if not found:
        print("未找到 ROM 元数据记录：该机型可能不是这种记录格式。")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
