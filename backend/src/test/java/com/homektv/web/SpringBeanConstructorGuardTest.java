package com.homektv.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动守卫：当某个 Spring 组件存在多个构造函数却没有任何一个标注 {@code @Autowired} 时，
 * Spring 无法在候选之间做出选择，会退而查找无参构造函数并抛出
 * {@code BeanInstantiationException: No default constructor found}，导致整个应用上下文启动失败。
 *
 * <p>该类问题不会被单元测试发现，因为只有真正刷新 Spring 上下文时才会暴露。而
 * {@code scripts/full_test_runner.py --backend local} 恰好把全部 {@code @SpringBootTest}
 * 类排除在本地范围之外，于是形成了"本地全绿但应用起不来"的系统性盲区。
 *
 * <p>本测试不加载任何 Spring 上下文、不依赖数据库或 Docker，毫秒级完成，
 * 专门用于堵住上述盲区。
 */
class SpringBeanConstructorGuardTest {

    @Test
    void multiConstructorBeansDeclareExactlyOneAutowiredConstructor() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        Set<BeanDefinition> candidates = scanner.findCandidateComponents("com.homektv");

        List<String> offenders = new ArrayList<>();
        for (BeanDefinition definition : candidates) {
            Class<?> type = Class.forName(
                    definition.getBeanClassName(), false, getClass().getClassLoader());
            if (type.isInterface() || type.isAnnotation() || type.isEnum()) {
                continue;
            }
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length < 2) {
                // 只有一个构造函数时 Spring 隐式使用它，无需 @Autowired。
                continue;
            }

            List<Constructor<?>> autowired = Arrays.stream(constructors)
                    .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                    .toList();
            boolean hasDefaultConstructor = Arrays.stream(constructors)
                    .anyMatch(constructor -> constructor.getParameterCount() == 0);

            if (autowired.size() > 1) {
                // Spring 在 determineCandidateConstructors 中对多个 @Autowired 构造函数直接抛
                // IllegalArgumentException: Invalid autowire-marked constructors。
                offenders.add(type.getName()
                        + " — " + autowired.size() + " 个构造函数标注了 @Autowired，"
                        + "Spring 启动时抛 " + IllegalArgumentException.class.getSimpleName());
            }
            else if (autowired.isEmpty() && !hasDefaultConstructor) {
                // 既没有 @Autowired 构造函数、也没有无参构造函数可用，
                // Spring 回退到 instantiateBean 时抛 BeanInstantiationException: No default constructor found。
                offenders.add(type.getName()
                        + " — " + constructors.length + " 个构造函数，"
                        + "既无 @Autowired 也无无参构造，Spring 抛 No default constructor found");
            }
        }

        offenders.sort(Comparator.naturalOrder());

        assertThat(offenders)
                .as("存在多个构造函数时，必须恰好有一个标注 @Autowired，否则 Spring 上下文无法启动")
                .isEmpty();
    }
}
