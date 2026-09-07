// pg_dump/restore can distribute an array cast without changing this exact CHECK.
// Normalize only the two reviewed spellings, never arbitrary SQL expressions.
export function normalizedMetadata(value) {
  const metadata=JSON.parse(value);
  const spellings=[
    "CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'HELD'::character varying])::text[])))",
    "CHECK (((status)::text = ANY (ARRAY[('ACTIVE'::character varying)::text, ('HELD'::character varying)::text])))"
  ];
  for(const constraint of metadata.constraints ?? []) {
    if(constraint.table_name==='accounts' && constraint.conname==='accounts_status_check' && spellings.includes(constraint.definition)) {
      constraint.definition='REVIEWED: status is ACTIVE or HELD';
    }
  }
  return metadata;
}
