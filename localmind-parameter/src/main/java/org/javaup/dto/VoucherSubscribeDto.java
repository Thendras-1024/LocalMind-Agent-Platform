package org.javaup.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 优惠券订阅
 * @author: 阿星不是程序员
 **/
@Data
@EqualsAndHashCode(callSuper = false)
public class VoucherSubscribeDto implements Serializable {
    
    @Serial
    private static final long serialVersionUID = 1L;
    
    /**
     * 优惠券id
     * */
    @NotNull
    private Long voucherId;
}
