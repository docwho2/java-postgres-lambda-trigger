
CREATE OR REPLACE FUNCTION record_change_lambda()
    RETURNS trigger
    LANGUAGE 'plpgsql'
    COST 100
    VOLATILE NOT LEAKPROOF
AS $BODY$
/**
Trigger Function that takes the Lambda Name as first parameter and 
calls the lambda with table name and operation and JSON records
for the old and new data as applicable.
*/
DECLARE
-- JSONB object we are building up to send to Lambda
json_all jsonb;
BEGIN

-- SQL equality with NULL never yields true; reject missing/blank arguments explicitly.
IF TG_NARGS < 1 OR TG_ARGV[0] IS NULL OR btrim(TG_ARGV[0]) = '' THEN
	RAISE EXCEPTION 'Lambda function name required as first parameter';
END IF;

-- JSON Structure with old an new set to null
json_all = '{ "old" : null, "new" : null }'::jsonb;

-- Operation being performed
json_all = jsonb_insert(json_all,'{TG_OP}',to_jsonb(TG_OP));
-- Table Name
json_all = jsonb_insert(json_all,'{TG_TABLE_NAME}',to_jsonb(TG_TABLE_NAME));

-- There is always a NEW record, except delete
IF TG_OP != 'DELETE' THEN
    json_all = jsonb_set(json_all,'{new}',to_jsonb(NEW));
END IF;

-- If this an update or delete we will have a OLD object to set
IF TG_OP != 'INSERT' THEN
    json_all = jsonb_set(json_all,'{old}',to_jsonb(OLD));
END IF;

-- Invoke the lambda with our JSON Object
-- Use 'Event' so it doesn't wait for function return value
PERFORM aws_lambda.invoke(aws_commons.create_lambda_function_arn(TG_ARGV[0]),json_all,'Event');

IF TG_OP != 'DELETE' THEN
    RETURN NEW;
ELSE
    RETURN OLD;
END IF;

END;
$BODY$;