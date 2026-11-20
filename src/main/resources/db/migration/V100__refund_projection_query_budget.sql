-- Recursive JSON estimates can trigger expensive LLVM compilation per item.
-- Scope this to the projection; restore the caller's setting on function exit.
ALTER FUNCTION project_amazon_sku_refunds(amazon_financial_transactions) SET jit = off;
