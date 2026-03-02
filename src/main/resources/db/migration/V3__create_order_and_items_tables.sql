CREATE TABLE customer_order (
    id            BIGSERIAL PRIMARY KEY,
    customer_id   BIGINT NOT NULL REFERENCES customer(id),
    status        VARCHAR(50) NOT NULL,
    total_amount  NUMERIC(12, 2) NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP
);

CREATE TABLE order_item (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES customer_order(id),
    product_id  BIGINT NOT NULL REFERENCES product(id),
    quantity    INTEGER NOT NULL,
    unit_price  NUMERIC(10, 2) NOT NULL,
    subtotal    NUMERIC(12, 2) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP
);
