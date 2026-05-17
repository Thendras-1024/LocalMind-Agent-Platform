package org.javaup.exception;

import lombok.Data;

/**
 * @program: 智邻生活 Agent 平台
 * @description: 参数错误
 * @author: 阿星不是程序员
 **/
@Data
public class ArgumentError {
	
	private String argumentName;
	
	private String message;
}
