-- Small-batch generated shop seed data for nearby-shop testing.
-- tb_shop is configured as a broadcast table, so the same rows must exist in hmdp_0 and hmdp_1.
-- Coordinate range is kept inside the current seed range:
-- x: 120.124691 - 120.158530, y: 30.310002 - 30.337252.
-- After direct SQL import, restart the backend so Redis GEO index is rebuilt from tb_shop.

SET NAMES utf8mb4;

USE hmdp_0;
START TRANSACTION;
INSERT INTO `tb_shop`
(`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `avg_price`, `sold`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES
(900001, '桥西巷口小馆', 1, 'https://p0.meituan.net/biztone/694233_1619500156517.jpeg', '拱宸桥/上塘', '桥西直街126号一层', 120.142300, 30.329600, 72, 3180, 924, 46, '10:30-21:30', NOW(), NOW()),
(900002, '运河声浪KTV', 2, 'https://p0.meituan.net/joymerchant/a575fd4adb0b9099c5c410058148b307-674435191.jpg', '运河上街', '台州路2号运河上街购物中心F3-302', 120.153200, 30.321400, 88, 1860, 365, 45, '12:00-02:00', NOW(), NOW()),
(900003, '青禾造型工作室', 3, 'https://img.meituan.net/msmerchant/232f8fdf09050838bd33fb24e79f30f9606056.jpg', '大关', '大关苑路58号', 120.156900, 30.314800, 96, 1120, 286, 47, '10:00-21:00', NOW(), NOW()),
(900004, '北城动能健身', 4, 'https://img.meituan.net/msmerchant/909434939a49b36f340523232924402166854.jpg', '北部新城', '杭行路666号万达广场B座4层', 120.127600, 30.335900, 45, 980, 214, 44, '07:00-23:00', NOW(), NOW()),
(900005, '水晶城舒缓足道', 5, 'https://img.meituan.net/msmerchant/e71a2d0d693b3033c15522c43e03f09198239.jpg', '水晶城', '上塘路458号水晶城购物中心5层', 120.158100, 30.310600, 118, 1560, 448, 48, '11:00-01:00', NOW(), NOW()),
(900006, '乐堤港亲子乐园', 7, 'https://p0.meituan.net/dpmerchantpic/63833f6ba0393e2e8722420ef33f3d40466664.jpg', '远洋乐堤港', '丽水路66号远洋乐堤港L2-208', 120.145200, 30.312600, 68, 760, 190, 45, '10:00-21:00', NOW(), NOW());
COMMIT;

USE hmdp_1;
START TRANSACTION;
INSERT INTO `tb_shop`
(`id`, `name`, `type_id`, `images`, `area`, `address`, `x`, `y`, `avg_price`, `sold`, `comments`, `score`, `open_hours`, `create_time`, `update_time`)
VALUES
(900001, '桥西巷口小馆', 1, 'https://p0.meituan.net/biztone/694233_1619500156517.jpeg', '拱宸桥/上塘', '桥西直街126号一层', 120.142300, 30.329600, 72, 3180, 924, 46, '10:30-21:30', NOW(), NOW()),
(900002, '运河声浪KTV', 2, 'https://p0.meituan.net/joymerchant/a575fd4adb0b9099c5c410058148b307-674435191.jpg', '运河上街', '台州路2号运河上街购物中心F3-302', 120.153200, 30.321400, 88, 1860, 365, 45, '12:00-02:00', NOW(), NOW()),
(900003, '青禾造型工作室', 3, 'https://img.meituan.net/msmerchant/232f8fdf09050838bd33fb24e79f30f9606056.jpg', '大关', '大关苑路58号', 120.156900, 30.314800, 96, 1120, 286, 47, '10:00-21:00', NOW(), NOW()),
(900004, '北城动能健身', 4, 'https://img.meituan.net/msmerchant/909434939a49b36f340523232924402166854.jpg', '北部新城', '杭行路666号万达广场B座4层', 120.127600, 30.335900, 45, 980, 214, 44, '07:00-23:00', NOW(), NOW()),
(900005, '水晶城舒缓足道', 5, 'https://img.meituan.net/msmerchant/e71a2d0d693b3033c15522c43e03f09198239.jpg', '水晶城', '上塘路458号水晶城购物中心5层', 120.158100, 30.310600, 118, 1560, 448, 48, '11:00-01:00', NOW(), NOW()),
(900006, '乐堤港亲子乐园', 7, 'https://p0.meituan.net/dpmerchantpic/63833f6ba0393e2e8722420ef33f3d40466664.jpg', '远洋乐堤港', '丽水路66号远洋乐堤港L2-208', 120.145200, 30.312600, 68, 760, 190, 45, '10:00-21:00', NOW(), NOW());
COMMIT;

SELECT 'hmdp_0' AS db_name, COUNT(*) AS generated_count FROM hmdp_0.tb_shop WHERE id BETWEEN 900001 AND 900006
UNION ALL
SELECT 'hmdp_1' AS db_name, COUNT(*) AS generated_count FROM hmdp_1.tb_shop WHERE id BETWEEN 900001 AND 900006;
