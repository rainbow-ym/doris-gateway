const fs=require('fs');
const assert=require('assert').strict;
const html=fs.readFileSync(process.argv[2],'utf8');
const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
new Function(script);
const ids=[...html.matchAll(/\bid="([^"]+)"/g)].map(x=>x[1]);
assert.equal(new Set(ids).size,ids.length,'duplicate element id');
for(const m of script.matchAll(/\$\('([^']+)'\)/g))assert(ids.includes(m[1]),'missing element '+m[1]);
console.log('PASS UI JavaScript syntax, unique IDs and referenced controls');
