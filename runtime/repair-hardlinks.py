#!/usr/bin/env python3
"""Recreates the hard links pacman could not make, as copies.

    repair-hardlinks.py ROOT CACHE_DIR PACMAN_CONF

Android refuses link() in app storage, so packages that ship hard links (binutils' ld, gcc's driver names,
tzdata, terminfo) are missing those names after installation. This reads each installed package's archive from
the cache and copies every hard link's target to the missing name.
"""
import os
import shutil
import subprocess
import sys
import tarfile


def installed(root, conf):
    out = subprocess.run(["pacman", "--config", conf, "--root", root, "-Q"], capture_output=True, text=True, check=True)
    return [line.split() for line in out.stdout.splitlines() if line.strip()]


def archive(cache, name, version):
    for arch in ("aarch64", "any"):
        for ext in ("xz", "zst", "gz"):
            path = os.path.join(cache, "%s-%s-%s.pkg.tar.%s" % (name, version, arch, ext))
            if os.path.isfile(path):
                return path
    return None


def open_archive(path):
    if path.endswith(".zst"):
        return tarfile.open(path, "r:zst")
    return tarfile.open(path)


def main(root, cache, conf):
    copied = missing = 0
    for name, version in installed(root, conf):
        path = archive(cache, name, version)
        if path is None:
            print("repair-hardlinks: no cached archive for %s %s" % (name, version), file=sys.stderr)
            missing += 1
            continue
        with open_archive(path) as tar:
            for member in tar:
                if not member.islnk():
                    continue
                dest = os.path.join(root, member.name)
                source = os.path.join(root, member.linkname)
                if os.path.lexists(dest) or not os.path.isfile(source):
                    continue
                os.makedirs(os.path.dirname(dest), exist_ok=True)
                shutil.copy2(source, dest)
                copied += 1
    print("repair-hardlinks: %d copies made, %d packages without an archive" % (copied, missing))
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:4]))
