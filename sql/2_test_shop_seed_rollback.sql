-- Roll back rows inserted by sql/2_test_shop_seed.sql.
-- Only the generated test ID range is removed.

SET NAMES utf8mb4;

USE hmdp_0;
START TRANSACTION;
DELETE FROM `tb_shop` WHERE `id` BETWEEN 900001 AND 900006;
COMMIT;

USE hmdp_1;
START TRANSACTION;
DELETE FROM `tb_shop` WHERE `id` BETWEEN 900001 AND 900006;
COMMIT;

SELECT 'hmdp_0' AS db_name, COUNT(*) AS remaining_generated_count FROM hmdp_0.tb_shop WHERE id BETWEEN 900001 AND 900006
UNION ALL
SELECT 'hmdp_1' AS db_name, COUNT(*) AS remaining_generated_count FROM hmdp_1.tb_shop WHERE id BETWEEN 900001 AND 900006;
