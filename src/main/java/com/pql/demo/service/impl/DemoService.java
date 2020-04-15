package com.pql.demo.service.impl;

import com.pql.demo.service.IDemoService;
import com.pql.mvcframework.annotation.PQLService;

@PQLService
public class DemoService implements IDemoService {

    public String get(String name) {
        return "my name is "+ name;
    }
}
