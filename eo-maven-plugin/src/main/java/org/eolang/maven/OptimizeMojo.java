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
package org.eolang.maven;

import com.jcabi.log.Logger;
import com.jcabi.xml.XML;
import com.jcabi.xml.XMLDocument;
import com.yegor256.xsline.Shift;
import com.yegor256.xsline.Train;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.cactoos.experimental.Threads;
import org.cactoos.iterable.Filtered;
import org.cactoos.iterable.Mapped;
import org.cactoos.number.SumOf;
import org.eolang.lints.Defect;
import org.eolang.lints.Program;
import org.eolang.maven.footprint.FpDefault;
import org.eolang.maven.optimization.OptSpy;
import org.eolang.maven.optimization.OptTrain;
import org.eolang.maven.optimization.Optimization;
import org.eolang.maven.tojos.ForeignTojo;
import org.eolang.maven.tojos.TojoHash;
import org.eolang.parser.TrParsing;
import org.w3c.dom.Node;
import org.xembly.Directives;
import org.xembly.Xembler;

/**
 * Optimize XML files, applying a number of mandatory XSL transformations
 * to them.
 *
 * <p>Also, this Mojo runs all available linters and adds errors to the
 * "error" XML element of every XMIR.</p>
 *
 * @since 0.1
 */
@Mojo(
    name = "optimize",
    defaultPhase = LifecyclePhase.PROCESS_SOURCES,
    threadSafe = true
)
public final class OptimizeMojo extends SafeMojo {

    /**
     * The directory where to transpile to.
     */
    public static final String DIR = "2-optimize";

    /**
     * Subdirectory for optimized cache.
     */
    static final String CACHE = "optimized";

    /**
     * The directory where to place intermediary files.
     */
    static final String STEPS = "2-optimization-steps";

    /**
     * Track optimization steps into intermediate XML files?
     *
     * @since 0.24.0
     * @checkstyle MemberNameCheck (7 lines)
     */
    @SuppressWarnings("PMD.LongVariable")
    @Parameter(property = "eo.trackOptimizationSteps", required = true, defaultValue = "false")
    private boolean trackOptimizationSteps;

    @Override
    public void exec() {
        final long start = System.currentTimeMillis();
        final Collection<ForeignTojo> tojos = this.scopedTojos().withXmir();
        final Optimization optimization = this.optimization();
        final int total = new SumOf(
            new Threads<>(
                Runtime.getRuntime().availableProcessors(),
                new Mapped<>(
                    tojo -> () -> this.optimized(tojo, optimization),
                    new Filtered<>(
                        ForeignTojo::notOptimized,
                        tojos
                    )
                )
            )
        ).intValue();
        if (total > 0) {
            Logger.info(
                this,
                "Optimized %d out of %d XMIR program(s) in %[ms]s",
                total, tojos.size(),
                System.currentTimeMillis() - start
            );
        } else {
            Logger.debug(this, "No XMIR programs out of %d optimized", tojos.size());
        }
    }

    /**
     * XMIR optimized to another XMIR.
     * @param tojo Foreign tojo
     * @param optimization Optimization to apply to XMIR
     * @return Amount of optimized XMIR files
     * @throws Exception If fails
     */
    private int optimized(final ForeignTojo tojo, final Optimization optimization)
        throws Exception {
        final Path source = tojo.xmir();
        final XML xmir = new XMLDocument(source);
        final String name = xmir.xpath("/program/@name").get(0);
        final Path base = this.targetDir.toPath().resolve(OptimizeMojo.DIR);
        final Path target = new Place(name).make(base, AssembleMojo.XMIR);
        tojo.withOptimized(
            new FpDefault(
                src -> OptimizeMojo.lint(optimization.apply(xmir)).toString(),
                this.cache.toPath().resolve(OptimizeMojo.CACHE),
                this.plugin.getVersion(),
                new TojoHash(tojo),
                base.relativize(target)
            ).apply(source, target)
        );
        return 1;
    }

    /**
     * Common optimization for all tojos.
     * @return Optimization for all tojos.
     */
    private Optimization optimization() {
        final Optimization opt;
        final Train<Shift> train = this.measured(new TrParsing());
        if (this.trackOptimizationSteps) {
            opt = new OptSpy(
                train,
                this.targetDir.toPath().resolve(OptimizeMojo.STEPS)
            );
        } else {
            opt = new OptTrain(train);
        }
        return opt;
    }

    /**
     * Find all possible linting defects.
     * @param xmir The XML before linting
     * @return XML after linting
     * @throws IOException If fails
     */
    private static XML lint(final XML xmir) throws IOException {
        final Directives dirs = new Directives();
        final Collection<Defect> defects = new Program(xmir).defects();
        if (!defects.isEmpty()) {
            dirs.xpath("/program").addIf("errors").strict(1);
            for (final Defect defect : defects) {
                if (OptimizeMojo.suppressed(xmir, defect)) {
                    continue;
                }
                dirs.add("error")
                    .attr("check", defect.rule())
                    .attr("severity", defect.severity().toString().toLowerCase(Locale.ENGLISH))
                    .set(defect.text());
                if (defect.line() > 0) {
                    dirs.attr("line", defect.line());
                }
                dirs.up();
            }
        }
        final Node node = xmir.node();
        new Xembler(dirs).applyQuietly(node);
        return new XMLDocument(node);
    }

    /**
     * This defect is suppressed?
     * @param xmir The XMIR
     * @param defect The defect
     * @return TRUE if suppressed
     */
    private static boolean suppressed(final XML xmir, final Defect defect) {
        return !xmir.nodes(
            String.format(
                "/program/metas/meta[head='unlint' and tail='%s']",
                defect.rule()
            )
        ).isEmpty();
    }
}
