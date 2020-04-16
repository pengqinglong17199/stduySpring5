package com.pql.mvcframework.v2.servlet;

import com.pql.mvcframework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

/**
 * 核心 DispatcherServlet
 * */
public class PQLDispatcherServlet extends HttpServlet {

    /**
     * 传说中的ioc容器 简化程序 只用hashMap 关注设计思想和原理
     * */
    private Map<String, Object> ioc = new HashMap<String, Object>();

    /**
     * 扫描到的bean类名集合
     * */
    private List<String> classNames = new ArrayList<String>();

    /**
     * application.xml的配置
     * */
    private Properties contextConfig = new Properties();

    /**
     * url和method的关系
     * */
    private Map<String, Method> handlerMapping = new HashMap<String, Method>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try{
            // 调用
            doDispatch(req, resp);
        }catch (Exception e){
            // 报错返回 500
            e.printStackTrace();
            resp.getWriter().write("500 Exception " + Arrays.toString(e.getStackTrace()));
        }
    }

    /**
     * post实际调用  通过url寻找映射
     * */
    private void doDispatch(HttpServletRequest request, HttpServletResponse response) throws Exception{
        String requestURL = request.getRequestURI();
        String contextPath = request.getContextPath();
        requestURL = requestURL.replace(contextPath, "").replaceAll("/+", "/");
        // 如果url不存在在映射中  说明404
        if(!this.handlerMapping.containsKey(requestURL)){
            response.getWriter().write("404 not Found!");
            return;
        }

        // 通过反射对controller方法进行调用
        Method method = (Method) this.handlerMapping.get(requestURL);
        // 获取方法的形参列表
        Class<?>[] parameterTypes = method.getParameterTypes();
        // 获取请求的参数列表
        Map<String, String[]> parameterMap = request.getParameterMap();
        // 实际存放的参数value列表
        Object[] parameterValues = new Object[parameterTypes.length];

        // 遍历方法形参
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];

            // 形参 = request 直接赋值
            if(parameterType == HttpServletRequest.class){
                parameterValues[i] = request;
                continue;

                // 形参 = response 直接赋值
            }else if(parameterType == HttpServletResponse.class){
                parameterValues[i] = response;
                continue;

                // 简单写 只考虑string
            }else if(parameterType == String.class){

                // 获取方法所形的注解 二维数组
                Annotation[][] parameterAnnotations = method.getParameterAnnotations();
                for (int j = 0; j < parameterAnnotations.length; j++) {
                    for (Annotation annotation : parameterAnnotations[j]) {
                        if(annotation instanceof PQLRequestParam){
                            String paramName = ((PQLRequestParam) annotation).value();
                            if(!"".equals(paramName.trim())){
                                String value = Arrays.toString(parameterMap.get(paramName))
                                        .replaceAll("\\[|\\]", "")
                                        .replaceAll("\\s",",");
                                parameterValues[i] = value;
                            }
                        }
                    }
                }
            }
        }
        Object bean = this.ioc.get(toLowerFirstCase(method.getDeclaringClass().getSimpleName()));
        method.invoke(bean, parameterValues);
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        // 1. 加载配置文件
        doLoadConfig(config.getInitParameter("contextConfigLocation"));

        // 2. 扫描bean
        doScanner(contextConfig.getProperty("scanPackage"));

        // 3. 初始化bean 并加入到ioc
        doInstance();

        // 4. 依赖注入
        doAutoWired();

        // 5. 初始化handlerMapping
        initHandlerMapping();

        System.out.println("pql spring framework is init");
    }

    /**
     * 加载配置文件
     * */
    private void doLoadConfig(String contextConfigLocation){
        InputStream fis = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try{
            contextConfig.load(fis);
        }catch (IOException e){
            e.printStackTrace();
        }finally {
            if(fis != null){
                try{
                    fis.close();
                }catch (IOException e){
                    e.printStackTrace();
                }
            }
        }
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
                classNames.add(clazzName);
            }
        }
    }

    /**
     * 容器式注册单例工厂
     */
    private void doInstance(){
        // 初始化 为di做准备
        if(classNames.isEmpty()){
            return;
        }

        try{
            for (String className : classNames) {
                Class<?> clazz = Class.forName(className);

                // 初始化有注解的bean
                if(clazz.isAnnotationPresent(PQLController.class)){
                    Object instance = clazz.newInstance();
                    // spring 默认bean名称首字母小写
                    String beanName = toLowerFirstCase(clazz.getSimpleName());
                    ioc.put(beanName, instance);
                }else if(clazz.isAnnotationPresent(PQLService.class)){
                    // 自定义的beanName
                    PQLService service = clazz.getAnnotation(PQLService.class);
                    String beanName = service.value();
                    // 默认bean名称首字母小写
                    if("".equals(beanName.trim())){
                        beanName = toLowerFirstCase(clazz.getSimpleName());
                    }
                    Object instance = clazz.newInstance();
                    ioc.put(beanName, instance);

                    // 接口默认bean
                    for (Class<?> i :clazz.getInterfaces()){
                        if(ioc.containsKey(i.getName())){
                            // 存在实现同一个接口的多个未自定义名称bean 抛出异常
                            throw new Exception("the " + i.getName() + "is exists!!");
                        }
                        ioc.put(i.getName(), instance);
                    }
                }
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    /**
     * 自动进行依赖注入
     * */
    private void doAutoWired(){
        if(ioc.isEmpty()){
            return;
        }

        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fields) {
                // 排除没有注解的字段
                if(!field.isAnnotationPresent(PQLAutowired.class)){
                    continue;
                }
                PQLAutowired autowired = field.getAnnotation(PQLAutowired.class);
                String beanName = autowired.value().trim();
                if("".equals(beanName)){
                    // 没有指定beanName 使用字段类名注入
                    beanName = field.getType().getName();
                }
                field.setAccessible(true);
                try{
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 初始化url和method的一对一关系
     * */
    private void initHandlerMapping(){
        if(ioc.isEmpty()){
            return;
        }

        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();

            // 排除非controller的bean
            if(!clazz.isAnnotationPresent(PQLController.class)){
                continue;
            }

            // 保存类url
            String baseUrl = "";
            if(clazz.isAnnotationPresent(PQLRequestMapping.class)){
                PQLRequestMapping requestMapping = clazz.getAnnotation(PQLRequestMapping.class);
                baseUrl = requestMapping.value();
            }

            // 默认获取所有public类型的方法
            for (Method method : clazz.getMethods()) {
                if(!method.isAnnotationPresent(PQLRequestMapping.class)){
                    continue;
                }

                PQLRequestMapping requestMapping = method.getAnnotation(PQLRequestMapping.class);
                String url = ("/" + baseUrl + "/" + requestMapping.value()).replaceAll("/+", "/");
                handlerMapping.put(url, method);
                System.out.println("Mapped: " + url + "," + method);
            }
        }
    }

    /**
     * 转换为首字母小写
     * */
    private String toLowerFirstCase(String simpleName){
        char[] chars = simpleName.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }
}
