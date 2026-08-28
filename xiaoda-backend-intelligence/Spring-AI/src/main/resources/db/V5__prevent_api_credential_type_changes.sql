CREATE FUNCTION prevent_api_credential_type_change()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'credential_type is immutable'
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_prevent_api_credential_type_change
    BEFORE UPDATE OF credential_type ON api_credential
    FOR EACH ROW
    WHEN (OLD.credential_type IS DISTINCT FROM NEW.credential_type)
    EXECUTE FUNCTION prevent_api_credential_type_change();
