/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016-2024 Objectionary.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package integration;

import com.jcabi.log.Logger;
import com.yegor256.MayBeSlow;
import com.yegor256.Mktmp;
import com.yegor256.MktmpResolver;
import com.yegor256.WeAreOnline;
import com.yegor256.farea.Farea;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Pattern;
import org.cactoos.iterable.Mapped;
import org.eolang.jucs.ClasspathSource;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.yaml.snakeyaml.Yaml;

/**
 * Integration test for simple snippets.
 *
 * <p>This test will/may fail if you change something in {@code to-java.xsl}
 * or some other place where Java sources are generated. This happens
 * because this test relies on {@code eo-runtime.jar}, which it finds in Maven
 * Central. Thus, when changes are made, it is recommended to disable this test.
 * Then, when new {@code eo-runtime.jar} is
 * released to Maven Central, you enable this test again.</p>
 * @since 0.1
 */
@ExtendWith(WeAreOnline.class)
@SuppressWarnings({"JTCOP.RuleAllTestsHaveProductionClass", "JTCOP.RuleNotContainsTestWord"})
@ExtendWith(MktmpResolver.class)
final class SnippetIT {
    @ParameterizedTest
    @ExtendWith(WeAreOnline.class)
    @ExtendWith(MayBeSlow.class)
    @ClasspathSource(value = "org/eolang/snippets/", glob = "**.yaml")
    void runsAllSnippets(final String yml, final @Mktmp Path temp) throws IOException {
        final Yaml yaml = new Yaml();
        final Map<String, Object> map = yaml.load(yml);
        final String file = map.get("file").toString();
        Assumptions.assumeFalse(map.containsKey("skip"));
        new Farea(temp).together(
            f -> {
                f.properties()
                    .set("project.build.sourceEncoding", StandardCharsets.UTF_8.name())
                    .set("project.reporting.outputEncoding", StandardCharsets.UTF_8.name());
                f.files().file("src/main").save(
                    Paths.get(System.getProperty("user.dir")).resolve("src/main")
                );
                f.files()
                    .file(String.format("src/main/eo/%s", file))
                    .write(String.format("%s\n", map.get("eo")).getBytes(StandardCharsets.UTF_8));
                f.dependencies().append(
                    "org.eolang",
                    "eo-runtime",
                    System.getProperty(
                        "eo.version",
                        "1.0-SNAPSHOT"
                    )
                );
                f.build()
                    .plugins()
                    .append(
                        "org.eolang",
                        "eo-maven-plugin",
                        System.getProperty(
                            "eo.version",
                            "1.0-SNAPSHOT"
                        )
                    )
                    .execution("compile")
                    .phase("generate-sources")
                    .goals("register", "assemble", "verify", "transpile")
                    .configuration()
                    .set("failOnWarning", Boolean.FALSE.toString());
                f.build()
                    .plugins()
                    .append("org.codehaus.mojo", "exec-maven-plugin", "3.1.1")
                    .execution("run")
                    .phase("test")
                    .goals("java")
                    .configuration()
                    .set("mainClass", "org.eolang.Main")
                    .set("arguments", map.get("args"));
                f.exec("clean", "test");
                final String log = f.log().content();
                Logger.debug(this, log);
                MatcherAssert.assertThat(
                    String.format("'%s' printed something wrong", yml),
                    log,
                    Matchers.allOf(
                        new Mapped<>(
                            ptn -> Matchers.matchesPattern(
                                Pattern.compile(ptn, Pattern.DOTALL | Pattern.MULTILINE)
                            ),
                            (Iterable<String>) map.get("out")
                        )
                    )
                );
            }
        );
    }
}
