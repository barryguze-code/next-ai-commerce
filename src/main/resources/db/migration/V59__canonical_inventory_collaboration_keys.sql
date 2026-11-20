-- Inventory collaboration keys are identifiers, not display dates. Normalize legacy US-short-date
-- suffixes so row summaries and conversation detail use the same stable ISO key.
ALTER TABLE collaboration_reviews DISABLE ROW LEVEL SECURITY;

UPDATE collaboration_reviews
SET subject_key=regexp_replace(subject_key,'\|[^|]*$','') || '|' ||
    to_char(to_date(regexp_replace(subject_key,'^.*\|',''),'MM/DD/YY'),'YYYY-MM-DD')
WHERE subject_type='INVENTORY'
  AND subject_key ~ '^.+\|[0-9]{1,2}/[0-9]{1,2}/[0-9]{2}$';

ALTER TABLE collaboration_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE collaboration_reviews FORCE ROW LEVEL SECURITY;
