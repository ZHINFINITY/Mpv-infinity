#!/usr/bin/env python3
"""Expose production motion shaders to the GPU policy regression fixture."""

import argparse
from pathlib import Path
import re


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--generate', type=Path, required=True)
    args = parser.parse_args()
    source = args.source.read_text(encoding='utf-8')
    shaders = []
    for name in ('sample_header', 'match_header', 'match_body', 'refine_header',
                 'synthesis_header', 'warp_body', 'synthesis_body'):
        match = re.search(r'^static const char ' + name +
                          r'\[\] = GLSL\(\n.*?^\);', source, re.M | re.S)
        if not match:
            raise ValueError('Missing shader: ' + name)
        shaders.append(match.group().replace(name + '[]', 'test_' + name + '[]', 1))
    args.generate.write_text('#define GLSL(...) #__VA_ARGS__\n' +
                             '\n\n'.join(shaders) + '\n#undef GLSL\n', encoding='utf-8')


if __name__ == '__main__':
    main()
