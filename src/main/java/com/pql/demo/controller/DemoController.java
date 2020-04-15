package com.pql.demo.controller;

import com.pql.demo.service.IDemoService;
import com.pql.mvcframework.annotation.PQLAutowired;
import com.pql.mvcframework.annotation.PQLController;
import com.pql.mvcframework.annotation.PQLRequestMapping;
import com.pql.mvcframework.annotation.PQLRequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@PQLController
@PQLRequestMapping(value = "/demo")
public class DemoController {

    @PQLAutowired
    private IDemoService demoService;

    @PQLRequestMapping(value = "/query")
    public void query(HttpServletRequest request, HttpServletResponse response, @PQLRequestParam("name") String name){
        String result = demoService.get(name);
        try{
            response.getWriter().write(result);
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    @PQLRequestMapping(value = "/add")
    public void add(HttpServletRequest request, HttpServletResponse response,
                      @PQLRequestParam("a") Integer a, @PQLRequestParam("b") Integer b){
        try{
            response.getWriter().write(a + "+" + b + "=" + (a+b));
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    @PQLRequestMapping(value = "/remove")
    public void add(HttpServletRequest request, HttpServletResponse response,
                    @PQLRequestParam("id") Integer id){
        try{
            response.getWriter().write("id");
        }catch (Exception e){
            e.printStackTrace();
        }
    }
}
