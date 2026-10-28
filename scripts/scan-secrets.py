#!/usr/bin/env python3
"""Audit source without printing matching values. --release also rejects local artifacts."""
import argparse
import hashlib
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

SKIP = {'.git', '.gradle', 'build', '.local', '.idea', '__pycache__', '.pytest_cache'}
LOCAL_NAMES = {'local.properties', '.DS_Store', 'settings-v1.xml'}
LOCAL_SUFFIXES = {'.apk', '.aab', '.jks', '.keystore', '.db', '.sqlite', '.sqlite3',
                  '.pcm', '.wav', '.mp3', '.log', '.hprof', '.pyc', '.pyo', '.zip'}
PUBLIC_HOSTS = {'developer.android.com', 'schemas.android.com', 'help.aliyun.com',
                'github.com', 'raw.githubusercontent.com', 'choosealicense.com',
                'opensource.org', 'www.apache.org', 'apache.org', 'services.gradle.org',
                'docs.gradle.org', 'gradle.org', 'repo.maven.apache.org',
                'repo1.maven.org', 'dl.google.com', 'plugins.gradle.org',
                'square.github.io', 'google.github.io', 'kotlinlang.org', 'publicsuffix.org',
                'mozilla.org', 'www.mozilla.org', 'android.googlesource.com'}
PATTERNS = {
    'API token': re.compile(r'\bsk-[A-Za-z0-9_.-]{20,}'),
    'cloud access key': re.compile(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b|\bLTAI[A-Za-z0-9]{16,}\b'),
    'GitHub token': re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})'),
    'private key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----'),
    'workspace identifier': re.compile(r'\bws-[a-z0-9]{10,}\b'),
    'personal home path': re.compile(r'/(?:Users|home)/[^/\s"\'<>]+/|[A-Za-z]:\\Users\\'),
    'email address': re.compile(r'\b[A-Za-z0-9._%+-]+@(?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,}\b'),
    'embedded credential': re.compile(
        r'(?i)(?:api[_-]?key|access[_-]?token|password|secret)\s*[:=]\s*["\'][A-Za-z0-9_./+-]{24,}["\']'),
}
WRAPPER_SHA256 = '2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046'
URL = re.compile(r'(?:https?|wss?)://[^\s<>"\'`\\)]+')

# Exact upstream legal texts may contain publisher URLs and contributor contacts.
# A changed file loses the exemption and is rejected; application secrets remain scanned.
VERIFIED_LEGAL = {
    'app/src/main/assets/licenses/UVCAndroid-Apache-2.0.txt':
        'c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4',
    'app/src/main/assets/licenses/libjpeg-turbo-LICENSE.txt':
        '7d8683a7f048e715e08b4cada8b7f0d9a6ab8afad88ed09c1143ef764ecbc0f2',
    'app/src/main/assets/licenses/libjpeg-turbo-README.ijg':
        '4b7b9f8c03bb8d60270dfd12684e70ab21e4abfd27e73905cd1a7c4cae6f5cdb',
    'app/src/main/assets/licenses/libyuv-AUTHORS.txt':
        '9bb006eaa8cc06d145c5234ae6b12f8fed6109e0b744d2c530f9b774c607e931',
}



def reserved_host(host):
    return host in {'example.org', 'example.com', 'example.net'} or host.endswith(
        ('.example.org', '.example.com', '.example.net', '.example', '.test', '.invalid'))


def public_host(host):
    return host in PUBLIC_HOSTS or reserved_host(host)


def audit(root, release=False):
    findings = []
    for path in sorted(root.rglob('*')):
        rel = path.relative_to(root)
        if '.git' in rel.parts:
            continue
        if path.is_symlink():
            findings.append((str(rel), 'symlink'))
            continue
        if path.is_dir():
            if release and path.name in SKIP:
                findings.append((str(rel), 'local/generated directory'))
            continue
        if any(part in SKIP for part in rel.parts):
            continue
        local = (path.name in LOCAL_NAMES or path.name.startswith('.env')
                 or path.suffix.lower() in LOCAL_SUFFIXES)
        if local:
            if release:
                findings.append((str(rel), 'local/generated file'))
            continue
        if rel.as_posix() == 'gradle/wrapper/gradle-wrapper.jar':
            if hashlib.sha256(path.read_bytes()).hexdigest() != WRAPPER_SHA256:
                findings.append((str(rel), 'unverified Gradle wrapper'))
            continue
        if rel.as_posix() in VERIFIED_LEGAL:
            if hashlib.sha256(path.read_bytes()).hexdigest() != VERIFIED_LEGAL[rel.as_posix()]:
                findings.append((str(rel), 'unverified upstream legal text'))
            continue
        try:
            source = path.read_text(encoding='utf-8')
        except (UnicodeError, OSError):
            findings.append((str(rel), 'unexpected binary/unreadable file'))
            continue
        for label, pattern in PATTERNS.items():
            for match in pattern.finditer(source):
                # Reserved-domain addresses are used only for fictional validation cases.
                if label == 'email address' and reserved_host(match.group().split('@')[-1]):
                    continue
                findings.append((str(rel), label))
                break
        for match in URL.finditer(source.replace('\\:', ':')):
            try:
                parsed = urlsplit(match.group())
                host = (parsed.hostname or '').lower()
                if not reserved_host(host) and (parsed.username is not None or parsed.password is not None
                        or re.search(r'(?i)(?:key|token|secret|password)=', parsed.query)):
                    findings.append((str(rel), 'credential-bearing URL'))
                    break
                if not public_host(host):
                    findings.append((str(rel), 'non-allowlisted URL'))
                    break
                production = rel.parts[:3] in {('app', 'src', 'main'), ('core', 'src', 'main')}
                if production and (reserved_host(host) or parsed.scheme in {'ws', 'wss'}):
                    findings.append((str(rel), 'bundled service URL'))
                    break
            except ValueError:
                findings.append((str(rel), 'invalid URL literal'))
                break
    return findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', action='store_true')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    if not args.root.is_dir():
        parser.error('--root must be an existing directory')
    issues = audit(args.root.resolve(), args.release)
    if issues:
        for path, category in issues:
            print(f'FAIL: {path}: {category}')
        return 1
    print('PASS: release tree' if args.release else 'PASS: source audit')
    print('No matching values printed. Pattern scans do not replace manual review.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
