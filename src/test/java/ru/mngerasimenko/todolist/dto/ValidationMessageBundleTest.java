package ru.mngerasimenko.todolist.dto;

import jakarta.validation.MessageInterpolator;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ConstructorDescriptor;
import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.ExecutableDescriptor;
import jakarta.validation.metadata.MethodDescriptor;
import jakarta.validation.metadata.MethodType;
import jakarta.validation.metadata.ParameterDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import ru.mngerasimenko.todolist.config.I18nConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сообщения bean validation — ключи бандлов, а не текст.
 *
 * <p>Ни ключ, которого нет в бандле, ни опечатка внутри текста ({@code {maxx}}) не роняют ни сборку,
 * ни запрос: интерполятор молча отдаёт шаблон как есть, и клиент получает
 * {@code "{validation.password.size}"} или {@code "от 1 до {maxx} символов"}. Поймать это можно
 * только здесь — перебором всех ограничений приложения.
 *
 * <p>Перебирается весь пакет приложения, а не только DTO: ограничение с захардкоженным текстом
 * появляется и на параметре контроллера ({@code @RequestParam @Size}), и на поле сущности.
 */
class ValidationMessageBundleTest {

    private static final String SCANNED_PACKAGE = "ru.mngerasimenko.todolist";

    private static final String KEY_PREFIX = "validation.";

    private static final Pattern PROJECT_KEY_TEMPLATE = Pattern.compile("^\\{(validation\\.[a-z0-9.-]+)}$");

    /**
     * Шаблоны по умолчанию у ограничений без {@code message}: их переводит сам Hibernate Validator
     * (в его jar есть и русский, и английский бандл).
     */
    private static final List<String> PROVIDER_DEFAULT_PREFIXES =
            List.of("{jakarta.validation.constraints.", "{org.hibernate.validator.constraints.");

    private static ValidatorFactory validatorFactory;
    private static AnnotationConfigApplicationContext context;

    @BeforeAll
    static void startContext() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        context = new AnnotationConfigApplicationContext(I18nConfig.class);
        // Surefire переиспользует форк: запрос, оставшийся в холдере от соседнего теста,
        // перебил бы локаль, которую этот тест передаёт явно, и проверка стала бы пустой.
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterAll
    static void stopContext() {
        validatorFactory.close();
        context.close();
    }

    @Test
    @DisplayName("у каждого ограничения сообщение — ключ validation.* или дефолт провайдера")
    void everyConstraintMessage_IsBundleKeyOrProviderDefault() {
        Map<String, String> offenders = new TreeMap<>();
        constraintsByLocation().forEach((location, constraint) -> {
            String template = constraint.getMessageTemplate();
            boolean projectKey = PROJECT_KEY_TEMPLATE.matcher(template).matches();
            boolean providerDefault = PROVIDER_DEFAULT_PREFIXES.stream().anyMatch(template::startsWith);
            if (!projectKey && !providerDefault) {
                offenders.put(location, template);
            }
        });

        assertThat(offenders).as("сообщения текстом вместо ключа бандла").isEmpty();
    }

    @Test
    @DisplayName("каждый использованный ключ есть и в messages_ru, и в messages_en")
    void everyKeyUsedByDto_ExistsInBothBundles() throws IOException {
        Set<String> usedKeys = usedKeys();
        assertThat(usedKeys).as("ключи, найденные в DTO").isNotEmpty();

        assertThat(validationKeys("messages_ru.properties")).as("messages_ru.properties").containsAll(usedKeys);
        assertThat(validationKeys("messages_en.properties")).as("messages_en.properties").containsAll(usedKeys);
    }

    @Test
    @DisplayName("в бандлах нет ключей validation.*, которые не использует ни один DTO")
    void bundles_HaveNoStaleValidationKeys() throws IOException {
        Set<String> usedKeys = usedKeys();

        assertThat(usedKeys).as("messages_ru.properties").containsAll(validationKeys("messages_ru.properties"));
        assertThat(usedKeys).as("messages_en.properties").containsAll(validationKeys("messages_en.properties"));
    }

    /**
     * Единственная проверка, которая видит опечатку внутри текста: {@code {maxx}} вместо {@code {max}},
     * незакрытая скобка, ключ на ограничении без такого атрибута. Hibernate Validator в этих случаях
     * ничего не бросает — он отдаёт шаблон как есть, и клиент читает «от 1 до {maxx} символов».
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("supportedLocales")
    @DisplayName("каждое сообщение полностью раскрывается — без остатков { } и не равно ключу")
    void everyMessage_InterpolatesCompletely(Locale locale) {
        MessageInterpolator interpolator =
                context.getBean(LocalValidatorFactoryBean.class).getMessageInterpolator();

        Map<String, String> offenders = new TreeMap<>();
        constraintsByLocation().forEach((location, constraint) -> {
            String template = constraint.getMessageTemplate();
            String message = interpolator.interpolate(template, new DescriptorContext(constraint), locale);
            if (message.indexOf('{') >= 0 || message.indexOf('}') >= 0 || message.equals(template)) {
                offenders.put(location, message);
            }
        });

        assertThat(offenders).as("нераскрытые сообщения для локали " + locale).isEmpty();
    }

    /**
     * Страховка к проверке выше: она осталась бы зелёной, даже если бы локаль вообще не влияла
     * на результат (например, интерполятор проигнорировал переданную локаль).
     */
    @Test
    @DisplayName("русские и английские тексты действительно разные")
    void messages_DifferBetweenLocales() {
        MessageInterpolator interpolator =
                context.getBean(LocalValidatorFactoryBean.class).getMessageInterpolator();
        ConstraintDescriptor<?> passwordSize = constraintsByLocation().entrySet().stream()
                .filter(entry -> "{validation.password.size}".equals(entry.getValue().getMessageTemplate()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow();

        String ru = interpolator.interpolate("{validation.password.size}",
                new DescriptorContext(passwordSize), Locale.forLanguageTag("ru"));
        String en = interpolator.interpolate("{validation.password.size}",
                new DescriptorContext(passwordSize), Locale.ENGLISH);

        assertThat(ru).isNotEqualTo(en);
    }

    /**
     * {@code MessageSourceMessageInterpolator} разрешает через {@code MessageSource} КАЖДЫЙ
     * {@code {токен}} шаблона, включая {@code {min}} и {@code {max}}. Ключ бандла с таким именем
     * перехватил бы подстановку у Hibernate Validator, и размерные сообщения молча сломались бы
     * везде сразу — например, «до Максимум символов».
     */
    @Test
    @DisplayName("ни один ключ бандла не совпадает с именем атрибута ограничения")
    void bundleKeys_DoNotShadowConstraintAttributes() throws IOException {
        Set<String> attributeNames = Set.of("min", "max", "value", "regexp", "flags", "groups", "payload",
                "inclusive", "integer", "fraction", "message");

        for (String bundle : List.of("messages_ru.properties", "messages_en.properties")) {
            assertThat(keys(bundle, key -> true)).as(bundle).doesNotContainAnyElementsOf(attributeNames);
        }
    }

    private static List<Locale> supportedLocales() {
        return List.of(Locale.forLanguageTag("ru"), Locale.ENGLISH);
    }

    private static Set<String> usedKeys() {
        return constraintsByLocation().values().stream()
                .map(constraint -> PROJECT_KEY_TEMPLATE.matcher(constraint.getMessageTemplate()))
                .filter(Matcher::matches)
                .map(matcher -> matcher.group(1))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** «Класс.поле @Аннотация» → дескриптор, по всем ограничениям всех классов приложения. */
    private static Map<String, ConstraintDescriptor<?>> constraintsByLocation() {
        Map<String, ConstraintDescriptor<?>> constraints = new TreeMap<>();
        for (Class<?> type : applicationClasses()) {
            BeanDescriptor bean = validatorFactory.getValidator().getConstraintsForClass(type);
            bean.getConstraintDescriptors().forEach(constraint ->
                    put(constraints, type.getName(), constraint));
            for (PropertyDescriptor property : bean.getConstrainedProperties()) {
                String location = type.getName() + "." + property.getPropertyName();
                property.getConstraintDescriptors().forEach(constraint -> put(constraints, location, constraint));
                for (ContainerElementTypeDescriptor element : property.getConstrainedContainerElementTypes()) {
                    element.getConstraintDescriptors().forEach(constraint -> put(constraints, location + "<>", constraint));
                }
            }
            // Параметры методов — это @RequestParam @Min в контроллерах под @Validated:
            // отдельная ветка метаданных, через getConstrainedProperties она не видна.
            for (MethodDescriptor method : bean.getConstrainedMethods(MethodType.NON_GETTER, MethodType.GETTER)) {
                putExecutable(constraints, type.getName() + "#" + method.getName(), method);
            }
            for (ConstructorDescriptor constructor : bean.getConstrainedConstructors()) {
                putExecutable(constraints, type.getName() + "#<init>", constructor);
            }
        }
        assertThat(constraints).as("ограничения в пакете " + SCANNED_PACKAGE).isNotEmpty();
        return constraints;
    }

    private static void putExecutable(Map<String, ConstraintDescriptor<?>> constraints, String location,
                                      ExecutableDescriptor executable) {
        for (ParameterDescriptor parameter : executable.getParameterDescriptors()) {
            // Индекс в ключе, а не только имя: у перегрузок и у нескольких конструкторов
            // одинаковые имена параметров схлопнулись бы в одну запись, и одно из ограничений
            // выпало бы из проверки молча.
            String at = location + "(" + parameter.getIndex() + ":" + parameter.getName() + ")";
            parameter.getConstraintDescriptors().forEach(constraint -> put(constraints, at, constraint));
        }
        executable.getReturnValueDescriptor().getConstraintDescriptors()
                .forEach(constraint -> put(constraints, location + "()", constraint));
    }

    private static void put(Map<String, ConstraintDescriptor<?>> constraints, String location,
                            ConstraintDescriptor<?> constraint) {
        String annotation = constraint.getAnnotation().annotationType().getSimpleName();
        constraints.put(location + " @" + annotation, constraint);
    }

    /**
      * Все классы приложения, включая вложенные ({@code ReorderListsRequest.Item}).
      * <p>
      * Тестовые классы отсеиваются, а сами классы загружаются БЕЗ инициализации: в
      * {@code AbstractIntegrationTest} статический блок поднимает Testcontainers-Postgres, и
      * инициализация при загрузке утащила бы docker в обычный unit-прогон — а на машине без
      * docker уронила бы его {@code ExceptionInInitializerError}.
      */
    private static List<Class<?>> applicationClasses() {
        String pattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
                + ClassUtils.convertClassNameToResourcePath(SCANNED_PACKAGE) + "/**/*.class";
        ClassLoader classLoader = ValidationMessageBundleTest.class.getClassLoader();
        List<Class<?>> classes = new ArrayList<>();
        try {
            MetadataReaderFactory readers = new SimpleMetadataReaderFactory();
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
                if (resource.getURL().getPath().contains("test-classes/")) {
                    continue;
                }
                String className = readers.getMetadataReader(resource).getClassMetadata().getClassName();
                classes.add(Class.forName(className, false, classLoader));
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        assertThat(classes).as("классы пакета " + SCANNED_PACKAGE).isNotEmpty();
        // Само-проверка фильтра выше: молча перестав работать, он вернул бы и тестовые классы,
        // и об этом никто бы не узнал — ограничений на них нет.
        assertThat(classes).map(Class::getName)
                .as("тестовые классы в скане")
                .noneMatch(name -> name.endsWith("Test") || name.endsWith("Tests"));
        return classes;
    }

    private static Set<String> validationKeys(String bundle) throws IOException {
        return keys(bundle, key -> key.startsWith(KEY_PREFIX));
    }

    private static Set<String> keys(String bundle, java.util.function.Predicate<String> filter) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = ValidationMessageBundleTest.class.getClassLoader().getResourceAsStream(bundle)) {
            assertThat(in).as(bundle).isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties.stringPropertyNames().stream()
                .filter(filter)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Контекст интерполяции поверх готового дескриптора: значения атрибутов берутся из него. */
    private record DescriptorContext(ConstraintDescriptor<?> descriptor) implements MessageInterpolator.Context {

        @Override
        public ConstraintDescriptor<?> getConstraintDescriptor() {
            return descriptor;
        }

        @Override
        public Object getValidatedValue() {
            return null;
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new ValidationException("Unsupported type: " + type);
        }
    }
}
