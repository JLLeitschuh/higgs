package io.higgs.core;

import io.higgs.core.reflect.ReflectionUtil;
import io.higgs.core.reflect.classpath.HiggsClassLoader;
import io.higgs.core.reflect.classpath.PackageScanner;
import io.higgs.core.reflect.dependency.DependencyProvider;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.constructor.Constructor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 */
public class HiggsServer {
    private static final HiggsClassLoader HIGGS_CLASS_LOADER = new HiggsClassLoader();
    public static Path BASE_PATH = Paths.get("./");
    protected final Set<MethodProcessor> methodProcessors = new HashSet<>();
    protected final Queue<ProtocolDetectorFactory> detectors = new ConcurrentLinkedDeque<>();
    protected final Set<ProtocolConfiguration> protocolConfigurations =
            Collections.newSetFromMap(new ConcurrentHashMap<ProtocolConfiguration, Boolean>());

    /**
     * A sorted set of methods. Methods are sorted in descending order of priority.
     */
    protected Queue<InvokableMethod> methods = new ConcurrentLinkedDeque<>();
    protected Queue<ObjectFactory> factories = new ConcurrentLinkedDeque<>();
    protected EventLoopGroup bossGroup = new NioEventLoopGroup();
    protected EventLoopGroup workerGroup = new NioEventLoopGroup();
    protected ServerBootstrap bootstrap = new ServerBootstrap();
    protected Channel channel;
    protected boolean detectSsl = true;
    protected boolean detectGzip = true;
    protected ServerConfig config = new ServerConfig();
    protected Logger log = LoggerFactory.getLogger(getClass());
    protected boolean onlyRegisterAnnotatedMethods = true;
    protected int port = 8080;
    Class<javax.ws.rs.Path> methodClass = javax.ws.rs.Path.class;

    public <C extends ServerConfig> HiggsServer setConfig(String configFile, Class<C> klass) {
        return setConfig(configFile, klass, null);
    }

    public <C extends ServerConfig> HiggsServer setConfig(String configFile, Class<C> klass, Constructor constructor) {
        if (configFile == null || configFile.isEmpty()) {
            configFile = "config.yml";
        }
        config = ConfigUtil.loadYaml(Paths.get(configFile), klass, constructor);
        this.port = config.port;
        DependencyProvider.global().add(config);
        return this;
    }

    /**
     * Start the server causing it to bind to the provided {@link #port}
     *
     * @throws UnsupportedOperationException if the server's already started
     */
    public void start() {
        start(new InetSocketAddress(port));
    }

    public void start(SocketAddress address) {
        if (channel != null) {
            throw new UnsupportedOperationException("Server already started");
        }
        try {
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        public void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new Transducer(detectSsl, detectGzip, detectors,
                                    methods));
                        }
                    });
            // Bind and start to accept incoming connections.
            channel = bootstrap.bind(address).sync().channel();
        } catch (Throwable t) {
            log.warn("Error starting server", t);
        }
    }

    public void stop() {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    /**
     * @return The Server's channel or null if it's not started
     */
    public Channel channel() {
        return channel;
    }

    public void setDetectSsl(boolean detectSsl) {
        this.detectSsl = detectSsl;
    }

    public void setDetectGzip(boolean detectGzip) {
        this.detectGzip = detectGzip;
    }

    /**
     * Chose whether ALL methods in a registered class are registered or
     * just the ones that have been explicitly annotated with {@link javax.ws.rs.Path}
     *
     * @param v if true only explicitly marked methods are registered
     */
    public void setOnlyRegisterAnnotatedMethods(boolean v) {
        onlyRegisterAnnotatedMethods = v;
    }

    public void registerProtocol(ProtocolConfiguration protocolConfiguration) {
        protocolConfigurations.add(protocolConfiguration);
        protocolConfiguration.initialize(this);
        registerProtocolDetectorFactory(protocolConfiguration.getProtocol());
        registerMethodProcessor(protocolConfiguration.getMethodProcessor());
    }

    /**
     * Registers a new protocol for the server to detect and handle
     *
     * @param factory the detector factories
     */
    public void registerProtocolDetectorFactory(ProtocolDetectorFactory factory) {
        detectors.add(factory);
    }

    public void registerMethodProcessor(MethodProcessor processor) {
        if (processor == null) {
            throw new IllegalArgumentException("Method processor cannot be null");
        }
        methodProcessors.add(processor);
    }

    /**
     * Discover all this package's classes, including sub packages and register them
     *
     * @param p the package to register
     */
    public void registerPackageAndSubpackages(Package p) {
        for (Class<?> c : HIGGS_CLASS_LOADER.loadPackage(p)) {
            registerObjectFactoryOrClass(c);
        }
    }

    private void registerObjectFactoryOrClass(Class<?> c) {
        if (ObjectFactory.class.isAssignableFrom(c)) {
            registerObjectFactory((Class<ObjectFactory>) c);
        } else {
            registerClass(c);
        }
    }

    public void registerObjectFactory(Class<ObjectFactory> c) {
        try {
            ObjectFactory factory = c.getConstructor(HiggsServer.class).newInstance(this);
            registerObjectFactory(factory);
        } catch (InstantiationException | InvocationTargetException e) {
            log.warn(String.format("Unable to create instance of ObjectFactory %s", c.getName()), e);
        } catch (IllegalAccessException e) {
            log.warn(String.format("Unable to access ObjectFactory %s", c.getName()), e);
        } catch (NoSuchMethodException e) {
            log.warn(String.format("%s does not have the required ObjectFactory(HiggsServer) constructor",
                    c.getName()));
        }
    }

    public void registerClass(Class<?> c) {
        registerMethods(c);
    }

    public void registerObjectFactory(ObjectFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("Cannot register a null object factories");
        }
        factories.add(factory);
    }

    /**
     * Register a class's methods
     *
     * @param klass the class to register or null if factories is set
     * @throws IllegalStateException if the class is null
     */
    public void registerMethods(Class<?> klass) {
        if (klass == null) {
            throw new IllegalArgumentException("Attempting to register null class");
        }
        //is the annotation applied to the whole class or not?
        boolean registerAllMethods = !klass.isAnnotationPresent(methodClass);
        Method[] m = ReflectionUtil.getAllMethods(klass);
        for (Method method : m) {
            if (onlyRegisterAnnotatedMethods && !method.isAnnotationPresent(methodClass)) {
                continue;
            }
            method.setAccessible(true);
            InvokableMethod im = null;
            for (MethodProcessor mp : methodProcessors) {
                im = mp.process(method, klass, factories);
                if (im != null) {
                    break;
                }
            }
            if (im == null) {
                log.warn(String.format("Method not registered. No method processor registered that can handle %s",
                        method.getName()));
                return;
            }
            if (registerAllMethods) {
                //register all methods is true
                methods.add(im);
                im.registered();
            } else {
                if (method.isAnnotationPresent(methodClass)) {
                    //if we're not registering all methods, AND this method has the annotation
                    if (methods.add(im)) {
                        im.registered();
                    } else {
                        throw new UnsupportedOperationException(String.format("Unable to add invokable method \n%s" +
                                "\n for path \n%s", im, im.path().getUri()));
                    }
                }
            }
        }
    }

    public void registerPackage(Package p) {
        registerPackage(p.getName());
    }

    public void registerPackage(String name) {
        for (Class<?> c : PackageScanner.get(name)) {
            registerObjectFactoryOrClass(c);
        }
    }

    /**
     * Remove all referentially equal object factories
     *
     * @param factory the factory to remove
     */
    public void deRegister(ObjectFactory factory) {
        for (ObjectFactory f : factories) {
            if (factory == f) {
                factories.remove(f);
            }
        }
    }

    /**
     * Remove all *identical* registered methods of this class
     * An identical class in one which matches registeredClass.equals(klass)
     * If such a match is found then all the methods registered for this class are removed
     *
     * @param klass the class whose methods are to be removed
     */
    public void deRegister(Class<?> klass) {
        for (InvokableMethod method : methods) {
            if (method.klass().equals(klass)) {
                methods.remove(method);
            }
        }
    }

    public <C extends ServerConfig> C getConfig() {
        return (C) config;
    }

    /**
     * @return The set of registered object factories
     */
    public Queue<ObjectFactory> getFactories() {
        return factories;
    }
}
