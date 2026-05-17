package org.javaup.dto;

import lombok.Data;

import java.util.List;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 滚动-结果
 * @author: 阿星不是程序员
 **/
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
