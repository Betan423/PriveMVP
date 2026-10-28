"""Infer a strict typed raw contract, with nine scenario-discriminated task branches."""
import json
from pathlib import Path

BASE=Path(__file__).resolve().parent


def infer(values):
    kinds={'dict':'object','list':'array','str':'string','int':'integer','float':'number','bool':'boolean','NoneType':'null'}
    groups={}
    for value in values:
        groups.setdefault(kinds[type(value).__name__],[]).append(value)
    if len(groups)>1:
        return {'anyOf':[infer(v) for v in groups.values()]}
    kind,group=next(iter(groups.items()))
    rule={'type':kind}
    if kind=='object':
        keys=list(dict.fromkeys(key for obj in group for key in obj))
        rule.update(properties={key:infer([obj[key] for obj in group if key in obj]) for key in keys},
                    required=[key for key in keys if all(key in obj for obj in group)],additionalProperties=False)
    if kind=='array':
        items=[item for array in group for item in array]
        rule['items']=infer(items) if items else {}
    return rule


if __name__=='__main__':
    source=json.loads((BASE/'dataset/source_raw_v5.json').read_text(encoding='utf-8'))
    schema=infer([source])
    schema.update({'$schema':'https://json-schema.org/draft/2020-12/schema','$id':'urn:ai-grandchild:raw:5.0',
                   'title':'AI Grandchild fictional raw source, NOT privacy processed'})
    for key,value in [('bundle_version','5.0-raw'),('synthetic',True),('privacy_processing_applied',False)]:
        schema['properties'][key]['const']=value
    branches=[]
    for sc in dict.fromkeys(t['scenario_id'] for t in source['task_requests']):
        branch=infer([t for t in source['task_requests'] if t['scenario_id']==sc])
        branch['properties']['scenario_id']['const']=sc
        branches.append(branch)
    schema['properties']['task_requests']['items']={'oneOf':branches}
    with (BASE/'source_schema.json').open('x',encoding='utf-8',newline='\n') as handle:
        json.dump(schema,handle,ensure_ascii=False,indent=2)
        handle.write('\n')
