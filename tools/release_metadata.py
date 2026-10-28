#!/usr/bin/env python3
import hashlib,json,os
from pathlib import Path
import xml.etree.ElementTree as ET
p=Path('delivery/Chocola.apk')
tests={'total':0,'failures':0,'errors':0,'skipped':0}
for report in Path('app/build/test-results').glob('test*UnitTest/TEST-*.xml'):
    root=ET.parse(report).getroot()
    for key,attribute in [('total','tests'),('failures','failures'),('errors','errors'),('skipped','skipped')]:
        tests[key]+=int(root.get(attribute,'0'))
data={'repository':os.environ.get('GITHUB_REPOSITORY'),'commit':os.environ.get('GITHUB_SHA'),
      'run':os.environ.get('GITHUB_RUN_ID'),'build':os.environ.get('GITHUB_RUN_NUMBER'),
      'tag':'build-'+os.environ.get('GITHUB_RUN_NUMBER','local'),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size,'tests':tests}
Path('delivery/Chocola-build.json').write_text(json.dumps(data,indent=2)+'\n')
print('Unit test results:',json.dumps(tests))
