#!/usr/bin/env python3
"""Unpacks pacman packages into a folder without hard links.

    extract-packages.py DEST PACKAGE_FILE...

Android refuses link() in app storage, and asking proot for one can crash it, so a hard link becomes a copy of
the file it names. Used for the build-only compiler, which never goes through pacman's database.
"""
import os
import shutil
import sys
import tarfile


def safe(dest, name):
    path = os.path.normpath(os.path.join(dest, name))
    if os.path.commonpath([dest, path]) != dest:
        raise ValueError("unsafe path %s" % name)
    return path


def extract(dest, package):
    links = []
    with tarfile.open(package) as tar:
        for member in tar:
            if member.name.startswith(".") and "/" not in member.name:
                continue
            path = safe(dest, member.name)
            if member.isdir():
                os.makedirs(path, exist_ok=True)
            elif member.issym():
                os.makedirs(os.path.dirname(path), exist_ok=True)
                if os.path.lexists(path):
                    os.unlink(path)
                os.symlink(member.linkname, path)
            elif member.islnk():
                links.append((path, safe(dest, member.linkname)))
            elif member.isfile():
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with tar.extractfile(member) as src, open(path, "wb") as out:
                    shutil.copyfileobj(src, out)
                os.chmod(path, member.mode & 0o7777)
    for path, source in links:
        if os.path.lexists(path):
            os.unlink(path)
        shutil.copy2(source, path)


def main(dest, *packages):
    dest = os.path.abspath(dest)
    os.makedirs(dest, exist_ok=True)
    for package in packages:
        extract(dest, package)
    print("extract-packages: %d packages into %s" % (len(packages), dest))
    return 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
