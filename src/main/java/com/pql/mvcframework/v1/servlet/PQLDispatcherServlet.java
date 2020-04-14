package com.pql.mvcframework.v1.servlet;

import com.pql.mvcframework.annotation.PQLAutowired;
import com.pql.mvcframework.annotation.PQLController;
import com.pql.mvcframework.annotation.PQLRequestMapping;
import com.pql.mvcframework.annotation.PQLService;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 核心 DispatcherServlet
 * */
public class PQLDispatcherServlet extends HttpServlet {

    private Map<String, Object> mapping = new HashMap<String, Object>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doGet(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try{
            // 调用
            doDispatch(req, resp);
        }catch (Exception e){
            // 报错返回 500
            resp.getWriter().write("500 Exception " + Arrays.toString(e.getStackTrace()));
        }
    }

    /**
     * post实际调用  通过url寻找映射
     * */
    private void doDispatch(HttpServletRequest request, HttpServletResponse response) throws Exception{
        String requestURL = request.getRequestURI();
        String contextPath = request.getContextPath();
        requestURL.replace(contextPath, "").replaceAll("/+","");
        // 如果url不存在在映射中  说明404
        if(!this.mapping.containsKey(requestURL)){
            response.getWriter().write("404 not Found!");
        }

        // 通过反射对controller方法进行调用
        Method method = (Method) this.mapping.get(requestURL);
        Map<String, String[]> parameterMap = request.getParameterMap();
        Object obj = this.mapping.get(method.getDeclaringClass().getName());
        Object[] params = {request, request, parameterMap.get("name")[0]};// 第一版简单写 暂时写死
        method.invoke(obj, params);
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        InputStream is = null;
        try{
            // 加载web.xml中的配置文件
            Properties configContext = new Properties();
            is = this.getClass().getClassLoader().getResourceAsStream(config.getInitParameter("contextConfigLocation"));
            configContext.load(is);

            // 通过scanPackage扫描所有bean
            String scanPackage = configContext.getProperty("scanPackage");
            doScanner(scanPackage);

            // 控制反转 取得实例
            for (String className : mapping.keySet()) {
                if(!className.contains(".")){
                    continue;
                }
                Class<?> clazz = Class.forName(className);
                // 判断是否为controller
                if(clazz.isAnnotationPresent(PQLController.class)){
                    // 容器式注册单例 保证实例为单例
                    mapping.put(className, clazz.newInstance());
                    String baseUrl = "";
                    // 判断是否有requestMapping注解映射
                    if(clazz.isAnnotationPresent(PQLRequestMapping.class)){
                        // 获得映射url
                        PQLRequestMapping requestMapping = clazz.getAnnotation(PQLRequestMapping.class);
                        baseUrl = requestMapping.value();
                    }
                    Method[] methods = clazz.getMethods();
                    for (Method method : methods) {
                        //去除没有注解的方法
                        if(!method.isAnnotationPresent(PQLRequestMapping.class)){
                             continue;
                        }
                        // 映射url到具体方法
                        PQLRequestMapping requestMapping = method.getAnnotation(PQLRequestMapping.class);
                        String url = (baseUrl + "/" + requestMapping.value()).replace("/+","/");
                        mapping.put(url, method);
                        System.out.println("Mapped " + url + "," + method);
                    }
                }else if(clazz.isAnnotationPresent(PQLService.class)){  // 判断是否为service

                    // 获得bean上的注解
                    PQLService service = clazz.getAnnotation(PQLService.class);
                    String beanName = service.value();
                    if("".equals(beanName)){
                        beanName = clazz.getName();
                    }

                    // 映射beanName到url (容器式注册单例 保证实例为单例)
                    Object instance = clazz.newInstance();
                    mapping.put(beanName, instance);
                    for (Class<?> i : clazz.getInterfaces()) {
                        mapping.put(i.getName(), instance);
                    }
                }else{
                    continue;
                }
            }

            // 依赖注入 注入成员
            for (Object object : mapping.values()) {
                if(object == null){
                    continue;
                }
                Class<?> clazz = object.getClass();

                // 判断实例是否是controller
                if(clazz.isAnnotationPresent(PQLController.class)){

                    // 获得实例所有字段
                    Field[] fields = clazz.getDeclaredFields();

                    // 为字段进行注入
                    for (Field field : fields) {

                        // 判断注入注解
                        if(!field.isAnnotationPresent(PQLAutowired.class)){
                            continue;
                        }

                        // 通过注解值获取beanName 默认为字段名
                        PQLAutowired autowired = field.getAnnotation(PQLAutowired.class);
                        String beanName = autowired.value();
                        if("".equals(beanName)){
                            beanName = field.getType().getName();
                        }

                        // 注入实例(容器式注册单例 保证实例为单例)
                        field.setAccessible(true);
                        try{
                            field.set(mapping.get(clazz.getName()), mapping.get(beanName));
                        }catch (Exception e){
                            e.printStackTrace();
                        }
                    }
                }
            }

        }catch (Exception e){

        }finally {
            if(is != null){
                try{
                    is.close();
                }catch (IOException e){
                    e.printStackTrace();
                }
            }
        }
        System.out.println("pql mvc framework is init");
    }

    /**
     * 扫描bean
     */
    private void doScanner(String packageName){
        // 获得包地址
        URL url = this.getClass().getClassLoader().getResource("/" + packageName.replaceAll("\\.", "/"));
        File classDir = new File(url.getFile());

        // 扫描class
        for (File file : classDir.listFiles()) {
            if(file.isDirectory()){
                doScanner(packageName + "." + file.getName());
            }else{
                if(!file.getName().endsWith(".class")){
                    continue;
                }
                String clazzName = (packageName + "." + file.getName().replace(".class", ""));
                mapping.put(clazzName, null);
            }
        }
    }
}
