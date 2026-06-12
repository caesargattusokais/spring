CREATE TABLE orders (id INT PRIMARY KEY, name VARCHAR(100));
CREATE TABLE audit_log (order_id INT, action VARCHAR(100));
