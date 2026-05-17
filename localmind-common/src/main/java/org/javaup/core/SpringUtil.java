package org.javaup.core;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import static org.javaup.constant.Constant.DEFAULT_PREFIX_DISTINCTION_NAME;
import static org.javaup.constant.Constant.PREFIX_DISTINCTION_NAME;

/**
 * @program: 智邻生活 Agent 平台 
 * @description: spring工具
 * @author: 阿星不是程序员
 **/
public class SpringUtil implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    
    private static ConfigurableApplicationContext configurableApplicationContext;
    
    
    public static String getPrefixDistinctionName(){
        return configurableApplicationContext.getEnvironment().getProperty(PREFIX_DISTINCTION_NAME,
                DEFAULT_PREFIX_DISTINCTION_NAME);
    }
    
    @Override
    public void initialize(final ConfigurableApplicationContext applicationContext) {
        configurableApplicationContext = applicationContext;
    }
}
