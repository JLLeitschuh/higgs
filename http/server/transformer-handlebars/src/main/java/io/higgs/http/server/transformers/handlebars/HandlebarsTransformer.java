package io.higgs.http.server.transformers.handlebars;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.HumanizeHelper;
import com.github.jknack.handlebars.Jackson2Helper;
import com.github.jknack.handlebars.MarkdownHelper;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.cache.HighConcurrencyTemplateCache;
import com.github.jknack.handlebars.context.FieldValueResolver;
import com.github.jknack.handlebars.context.JavaBeanValueResolver;
import com.github.jknack.handlebars.context.MapValueResolver;
import com.github.jknack.handlebars.context.MethodValueResolver;
import io.higgs.core.ConfigUtil;
import io.higgs.core.reflect.dependency.DependencyProvider;
import io.higgs.http.server.HttpRequest;
import io.higgs.http.server.HttpResponse;
import io.higgs.http.server.config.HandlebarsConfig;
import io.higgs.http.server.protocol.HttpMethod;
import io.higgs.http.server.resource.MediaType;
import io.higgs.http.server.transformers.BaseTransformer;
import io.higgs.http.server.transformers.ResponseTransformer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelHandlerContext;
import org.kohsuke.MetaInfServices;

import javax.ws.rs.WebApplicationException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import static io.higgs.http.server.resource.MediaType.APPLICATION_FORM_URLENCODED_TYPE;
import static io.higgs.http.server.resource.MediaType.APPLICATION_XHTML_XML_TYPE;
import static io.higgs.http.server.resource.MediaType.TEXT_HTML_TYPE;
import static io.higgs.http.server.resource.MediaType.WILDCARD_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.EXPECTATION_FAILED;
import static io.netty.handler.codec.http.HttpResponseStatus.FAILED_DEPENDENCY;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;

/**
 * @author Courtney Robinson <courtney@crlog.info>
 */
//@ProviderFor(ResponseTransformer.class)
@MetaInfServices(ResponseTransformer.class)
public class HandlebarsTransformer extends BaseTransformer {
    public static final String HANDLE_BARS = "{{handlebars}}";
    protected HandlebarsConfig config;
    protected Handlebars handlebars;
    protected HiggsTemplateLoader loader;

    public HandlebarsTransformer() {
        config = ConfigUtil.loadYaml("handlebars_config.yml", HandlebarsConfig.class);
        setPriority(config.priority);
        addSupportedTypes(WILDCARD_TYPE, TEXT_HTML_TYPE, APPLICATION_FORM_URLENCODED_TYPE, APPLICATION_XHTML_XML_TYPE);
        if (DependencyProvider.global().get(HANDLE_BARS) == null) {
            DependencyProvider.global().put(HANDLE_BARS, new HashMap<String, Object>());
        }
        loader = new HiggsTemplateLoader(config);
        handlebars = new Handlebars(loader);
        loadHelpers();
        if (config.enable_humanize_helper) {
            HumanizeHelper.register(handlebars);
        }
        if (config.enable_jackson_helper) {
            handlebars.registerHelper("json", Jackson2Helper.INSTANCE);
        }
        if (config.enable_markdown_helper) {
            handlebars.registerHelper("md", MarkdownHelper.INSTANCE);
        }
        if (config.cache_templates) {
            handlebars.with(new HighConcurrencyTemplateCache());
        }
    }

    protected void loadHelpers() {
        Iterator<HandlebarHelper> providers = ServiceLoader.load(HandlebarHelper.class).iterator();
        while (providers.hasNext()) {
            try {
                HandlebarHelper helper = providers.next();
                handlebars.registerHelper(helper.getName(), helper);
            } catch (ServiceConfigurationError sce) {
                log.warn("Unable to register Handlebar helper factory", sce);
            }
        }
    }

    @Override
    public boolean canTransform(Object response, HttpRequest request, MediaType mediaType,
                                HttpMethod method, ChannelHandlerContext ctx) {
        return method == null ? super.canTransform(response, request, mediaType, method, ctx) :
                method.hasTemplate() && super.canTransform(response, request, mediaType, method, ctx);
    }

    @Override
    public void transform(Object response, HttpRequest request, HttpResponse res, MediaType mediaType,
                          HttpMethod method, ChannelHandlerContext ctx) {
        String tpl = "error/default";
        if (isError(response)) {
            determineErrorStatus(res, (Throwable) response);
            if (!(response instanceof WebApplicationException)) {
                response = new WebApplicationException((Throwable) response, 500);
            }
        } else {
            if (method == null) {
                //should only ever happen if isError is true which means we should never get here
                throw new WebApplicationException(EXPECTATION_FAILED.code());
            }
            if (!method.hasTemplate()) {
                throw new WebApplicationException("HandlebarsTransformer only supports a template " +
                        "value, to use fragments use mustacheTransformer's inheritance", FAILED_DEPENDENCY.code());
            }
            tpl = method.getTemplate();
        }
        ByteBuf buf = ctx.alloc().heapBuffer();
        OutputStream in = new ByteBufOutputStream(buf);
        Writer writer = new OutputStreamWriter(in);

        try {
            Template template = handlebars.compile(tpl);
            template.apply(scopes(response, request, method), writer);
            //flush data to byte buf
            writer.flush();
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            setResponseContent(res, data);
        } catch (IOException e) {
            log.warn("Failed to write the results of a mustacheTransformer execution", e);
            res.setStatus(INTERNAL_SERVER_ERROR);
            setResponseContent(res, new byte[0]);
        }
    }

    private Context scopes(Object response, HttpRequest request, HttpMethod method) {
        Context ctx = Context.newBuilder(response)
                .resolver(
                        JavaBeanValueResolver.INSTANCE,
                        MapValueResolver.INSTANCE,
                        FieldValueResolver.INSTANCE,
                        MethodValueResolver.INSTANCE
                ).build();

        //${_query} ,${_form},${_files},${_session},${_cookies},${_request},${_response},${_server}
        Map<String, ?> anything = DependencyProvider.global().get(HANDLE_BARS);
        ctx.data("_query", request.getQueryParams())
                .data("_form", request.getFormParam())
                .data("_files", request.getFormFiles())
                .data("_subject", request.getSubject())
                .data("_session", request.getSubject().getSession())
                .data("_cookies", request.getCookies())
                .data("_request", request)
                .data("_response", response)
                        //add anything the user sets
                .data(anything);
        if (method != null) {
            ctx.data("_validation", method.getValidationResult());
        }
        return ctx;
    }

    @Override
    public ResponseTransformer instance() {
        return new HandlebarsTransformer();
    }
}
