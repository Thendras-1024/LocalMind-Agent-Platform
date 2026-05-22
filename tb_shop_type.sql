/*
 Navicat Premium Dump SQL

 Source Server         : 127.0.0.1
 Source Server Type    : MySQL
 Source Server Version : 80040 (8.0.40)
 Source Host           : localhost:3306
 Source Schema         : hmdp_0

 Target Server Type    : MySQL
 Target Server Version : 80040 (8.0.40)
 File Encoding         : 65001

 Date: 22/05/2026 14:36:26
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for tb_shop_type
-- ----------------------------
DROP TABLE IF EXISTS `tb_shop_type`;
CREATE TABLE `tb_shop_type`  (
  `id` bigint UNSIGNED NOT NULL COMMENT '主键',
  `name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '类型名称',
  `icon` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '图标',
  `sort` int UNSIGNED NULL DEFAULT NULL COMMENT '顺序',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = COMPACT;

-- ----------------------------
-- Records of tb_shop_type
-- ----------------------------
INSERT INTO `tb_shop_type` VALUES (1, '美食', '/types/ms.png', 1, '2021-12-22 12:17:47', '2021-12-23 03:24:31');
INSERT INTO `tb_shop_type` VALUES (2, 'KTV', '/types/KTV.png', 2, '2021-12-22 12:18:27', '2021-12-23 03:24:31');
INSERT INTO `tb_shop_type` VALUES (3, '丽人·美发', '/types/lrmf.png', 3, '2021-12-22 12:18:48', '2021-12-23 03:24:31');
INSERT INTO `tb_shop_type` VALUES (4, '健身运动', '/types/jsyd.png', 10, '2021-12-22 12:19:04', '2021-12-23 03:24:31');
INSERT INTO `tb_shop_type` VALUES (5, '按摩·足疗', '/types/amzl.png', 5, '2021-12-22 12:19:27', '2021-12-23 03:24:31');
INSERT INTO `tb_shop_type` VALUES (6, '美容SPA', '/types/spa.png', 6, '2021-12-22 12:19:35', '2021-12-23 03:24:31');
INSERT INTO `tb_shop_type` VALUES (7, '亲子游乐', '/types/qzyl.png', 7, '2021-12-22 12:19:53', '2021-12-23 03:24:31');
INSERT INTO `tb_shop_type` VALUES (8, '酒吧', '/types/jiuba.png', 8, '2021-12-22 12:20:02', '2021-12-23 03:24:31');
INSERT INTO `tb_shop_type` VALUES (9, '轰趴馆', '/types/hpg.png', 9, '2021-12-22 12:20:08', '2021-12-23 03:24:31');
INSERT INTO `tb_shop_type` VALUES (10, '美睫·美甲', '/types/mjmj.png', 4, '2021-12-22 12:21:46', '2021-12-23 03:24:31');

SET FOREIGN_KEY_CHECKS = 1;
