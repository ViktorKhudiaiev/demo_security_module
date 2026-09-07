import test from 'node:test';
import assert from 'node:assert/strict';
import {normalizedMetadata} from './migration-metadata.mjs';
const a="CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'HELD'::character varying])::text[])))";
const b="CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('HELD'::character varying)::text])))";
const meta=(definition,name='accounts_status_check')=>JSON.stringify({constraints:[{table_name:'accounts',conname:name,definition}],indexes:[]});
test('only the exact equivalent status constraint is normalized',()=>{
 assert.deepEqual(normalizedMetadata(meta(a)),normalizedMetadata(meta(b)));
 for(const changed of [b.replace('HELD','CLOSED'),b.replace('ANY','ALL'),b+' OR true',b.replace('status','name')])assert.notDeepEqual(normalizedMetadata(meta(a)),normalizedMetadata(meta(changed)));
 assert.notDeepEqual(normalizedMetadata(meta(a,'another_check')),normalizedMetadata(meta(b,'another_check')));
});
