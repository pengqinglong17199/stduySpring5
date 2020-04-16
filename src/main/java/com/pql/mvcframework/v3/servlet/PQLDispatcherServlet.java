package com.pql.mvcframework.v3.servlet;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static jdk.nashorn.api.scripting.ScriptUtils.convert;

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
    private List<Handler> handlerMapping = new ArrayList<Handler>();

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

        Handler handler = this.getHandler(request);
        if(handler == null) {
            // 如果url不存在在映射中  说明404
            response.getWriter().write("404 not Found!");
            return;

        }

        // 获取方法的形参列表
        Class<?>[] parameterTypes = handler.method.getParameterTypes();
        // 实际存放的参数value列表
        Object[] parameterValues = new Object[parameterTypes.length];
        // 获取请求的参数列表
        Map<String, String[]> parameterMap = request.getParameterMap();

        // 遍历方法形参
        for (Map.Entry<String, String[]> param : parameterMap.entrySet()) {
            String value = Arrays.toString(param.getValue())
                    .replaceAll("\\[|\\]", "")
                    .replaceAll("\\s", ",");
            if(!handler.paramIndexMapping.containsKey(param.getKey())){
                continue;
            }
            Integer index = handler.paramIndexMapping.get(param.getKey());
            parameterValues[index] = convert(parameterTypes[index], value);
        }
        if(handler.paramIndexMapping.containsKey(HttpServletRequest.class.getName())){
            Integer index = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
            parameterValues[index] = request;
        }

        //
        if(handler.paramIndexMapping.containsKey(HttpServletResponse.class.getName())){
            Integer index = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
            parameterValues[index] = response;
        }

        Object invoke = handler.method.invoke(handler.controller, parameterValues);
        if(invoke == null || invoke instanceof Void){
            return;
        }
        response.getWriter().write(invoke.toString());
    }

    /**
     * 通过请求获取handler
     * */
    private Handler getHandler(HttpServletRequest request) throws Exception {
        if(handlerMapping.isEmpty()){
            return null;
        }
        String requestURL = request.getRequestURI();
        String contextPath = request.getContextPath();
        requestURL = requestURL.replace(contextPath, "").replaceAll("/+", "/");
        for (Handler handler : handlerMapping) {
            try{
                Matcher matcher = handler.pattern.matcher(requestURL);
                if(!matcher.matches()){
                    continue;
                }
                return handler;
            }catch (Exception e){
                throw e;
            }
        }
        return null;
    }

    /**
     * http基于字符串协议 所以url传过来的参数都是string类型
     * 只需要把string转换为对应类型
     * */
    private Object convert(Class<?> type, String value){
        if(Integer.class == type){
            return Integer.valueOf(value);
        }
        return value;
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
                // 增加正则解析
                Pattern pattern = Pattern.compile(url);
                handlerMapping.add(new Handler(pattern, entry.getValue(), method));
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

    /**
     * 内部类 handler
     * 记录controller中RequestMapping和method的关系
     * */
    private class Handler{

        protected Object controller;  // bean实例
        protected Method method;      // 方法实例
        protected Pattern pattern;    // 正则
        protected Map<String, Integer> paramIndexMapping;   // 参数顺序

        /**
         * 构造handler基本参数
         * */
        protected Handler(Pattern pattern, Object controller, Method method){
            this.pattern = pattern;
            this.controller = controller;
            this.method = method;
            paramIndexMapping = new HashMap<String, Integer>();
        }

        /**
         * 提取方法中加了注解的参数
         * */
        private void putParamIndexMapping(Method method){
            // 获取方法所形的注解 二维数组
            Annotation[][] parameterAnnotations = method.getParameterAnnotations();
            for (int i = 0; i < parameterAnnotations.length; i++) {
                for (Annotation annotation : parameterAnnotations[i]) {
                    if(annotation instanceof PQLRequestParam){
                        String paramName = ((PQLRequestParam) annotation).value();
                        if(!"".equals(paramName.trim())){
                            paramIndexMapping.put(paramName, i);
                        }
                    }
                }
            }
            // 提取方法中的request和response参数
            Class<?>[] parameterTypes = method.getParameterTypes();
            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];
                if(parameterType == HttpServletRequest.class
                || parameterType == HttpServletResponse.class){
                    paramIndexMapping.put(parameterType.getName(), i);
                }
            }
        }
    }
}
