-- Последовательность для атомарной генерации номеров обращений (TD-00001, TD-00002...)
CREATE SEQUENCE IF NOT EXISTS ticket_number_seq START WITH 1 INCREMENT BY 1;