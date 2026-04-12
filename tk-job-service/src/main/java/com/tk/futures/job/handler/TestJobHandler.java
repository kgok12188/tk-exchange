package com.tk.futures.job.handler;

import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Service;

@Service
public class TestJobHandler {

    @XxlJob("xxlJob_text")
    public void test() {
        System.out.println("==============");
    }

}
