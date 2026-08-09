package cn.iocoder.yudao.module.transport.integration.algorithm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

/**
 * 算法适配层装配：独立的 ObjectMapper 与 RestTemplate，不依赖全局 Web 配置，
 * 保证 batchStart/batchEnd 等字段按 ISO 8601 带时区序列化（契约强制要求）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AlgorithmProperties.class)
public class AlgorithmAdapterConfiguration {

    public static final String ALGORITHM_OBJECT_MAPPER = "algorithmObjectMapper";
    public static final String ALGORITHM_REST_TEMPLATE = "algorithmRestTemplate";

    @Bean(ALGORITHM_OBJECT_MAPPER)
    public ObjectMapper algorithmObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
    }

    @Bean(ALGORITHM_REST_TEMPLATE)
    public RestTemplate algorithmRestTemplate(AlgorithmProperties properties) {
        RestTemplate restTemplate = new RestTemplateBuilder()
                .rootUri(properties.getBaseUrl())
                .requestFactory(() -> {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
                    factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
                    return factory;
                })
                .build();
        // 显式替换 Jackson 转换器，避免默认转换器把 OffsetDateTime 序列化成时间戳
        restTemplate.getMessageConverters().removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
        restTemplate.getMessageConverters().add(new MappingJackson2HttpMessageConverter(algorithmObjectMapper()));
        return restTemplate;
    }
}
