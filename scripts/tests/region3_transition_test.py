#!/usr/bin/env python3
"""Guard this branch-only attenuation experiment; not a visual-quality claim."""
import ast
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/main/cpp/mpvflow/mpvflow_gpu.c'
text = SOURCE.read_text()
match = re.search(r'static const char shader_synthesize\[\] =\n(.*?);\n', text, re.S)
shader = ''.join(ast.literal_eval(s) for s in re.findall(r'"(?:\\.|[^"\\])*"', match[1]))

def smoothstep(a, b, x):
    t = min(1.0, max(0.0, (x-a)/(b-a)))
    return t*t*(3.0-2.0*t)

def trust(luma, color, chroma, uniqueness):
    return ((1-smoothstep(.025,.08,luma)) *
            (1-smoothstep(.06,.12,color)*smoothstep(.025,.06,chroma)) *
            (1-(1-smoothstep(.04,.08,uniqueness))*smoothstep(.06,.10,color)))

class Region3TransitionTest(unittest.TestCase):
    def test_shader_matches_reference_and_attenuates_only(self):
        for fragment in ('1.0-smoothstep(0.025,0.08,lumaDelta)',
                         '1.0-smoothstep(0.06,0.12,colorDelta)*smoothstep(0.025,0.06,chromaDelta)',
                         '1.0-(1.0-smoothstep(0.04,0.08,uniqueness))*smoothstep(0.06,0.10,colorDelta)',
                         'return edgeTrust*chromaTrust*ambiguousTrust;',
                         'region3LocalTrust=mix(1.0,smoothstep(0.30,0.75,neighborhoodMinimum),neighborhoodActivation);',
                         'if(!softBlend) boundaryTrust*=region3LocalTrust*region3TransitionTrust(lumaDelta,colorDelta,chromaDelta,min(f.w,b.w));'):
            self.assertIn(fragment, shader)
        self.assertEqual(shader.count('region3TransitionTrust('), 2)
        self.assertNotIn('warpTrust*=', shader)
        self.assertEqual(shader.count('float warpTrust=smoothstep(0.0,0.60,confidence)*boundaryTrust;'), 1)
        # No added image/flow/color loads, no spreading of neighboring vectors.
        helper = shader.split('float region3TransitionTrust(',1)[1].split('\n',1)[0]
        for forbidden in ('imageLoad', 'texelFetch', 'texture', 'flowAt', 'colorAt'):
            self.assertNotIn(forbidden, helper)

    def test_safety_and_soft_blend_are_preserved(self):
        for fragment in ('return cycleQuality*matchQuality*uniquenessQuality*valid;',
                         'else if(boundaryConflict) outc=FLOW_RESULT(mix(texelFetch(frame0,p,0),texelFetch(frame1,p,0),pc.timestep),12,confidence,captureReason);',
                         'float softWarpTrust=smoothstep(0.0,0.60,confidence)*boundaryTrust;',
                         'float confidence=softBlend?max(confidence0,confidence1):min(confidence0,confidence1);',
                         'if(confidence<=0.0||(softBlend&&confidence<=0.15))',
                         'if(cut>0.1882353)', 'outc.a=1.0;'):
            self.assertIn(fragment, shader)
        self.assertNotIn('region3', shader.split('if(softBlend){ vec4 fallback=',1)[1].split('else if(boundaryConflict)',1)[0])

    def test_continuous_bounded_fade(self):
        for uniqueness in (0,.04,.06,.08,.3,1):
            for mode in ('luma','chroma','ambiguous'):
                values = []
                for i in range(1001):
                    x=i/1000*.16
                    args = (x,0,0,uniqueness) if mode=='luma' else ((0,x,x,uniqueness) if mode=='chroma' else (0,x,0,uniqueness))
                    values.append(trust(*args))
                self.assertTrue(all(0 <= v <= 1 for v in values))
                self.assertTrue(all(a >= b for a,b in zip(values,values[1:])))
                self.assertLess(max(abs(a-b) for a,b in zip(values,values[1:])),.02)
        self.assertEqual(trust(0,0,0,1),1)
        self.assertEqual(trust(.08,0,0,1),0)
        self.assertEqual(trust(0,.12,.06,1),0)
        self.assertEqual(trust(0,.10,0,0),0)

if __name__ == '__main__':
    unittest.main()
