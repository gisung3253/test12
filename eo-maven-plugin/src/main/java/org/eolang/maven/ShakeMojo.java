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
import java.nio.file.Path;
import java.util.Collection;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.cactoos.experimental.Threads;
import org.cactoos.iterable.Filtered;
import org.cactoos.iterable.Mapped;
import org.cactoos.number.SumOf;
import org.eolang.maven.footprint.FpDefault;
import org.eolang.maven.optimization.OptSpy;
import org.eolang.maven.optimization.OptTrain;
import org.eolang.maven.optimization.Optimization;
import org.eolang.maven.tojos.ForeignTojo;
import org.eolang.maven.tojos.TojoHash;

/**
 * Shake (prepare) XML files after optimizations for translation to java.
 *
 * @since 0.33.0
 */
@Mojo(
    name = "shake",
    defaultPhase = LifecyclePhase.PROCESS_SOURCES,
    threadSafe = true
)
public final class ShakeMojo extends SafeMojo {
    /**
     * The directory where to shake to.
     */
    public static final String DIR = "3-shake";

    /**
     * Subdirectory for shaken cache.
     */
    static final String CACHE = "shaken";

    /**
     * The directory where to place intermediary files.
     */
    static final String STEPS = "3-shake-steps";

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
    void exec() {
        final long start = System.currentTimeMillis();
        final Collection<ForeignTojo> tojos = this.scopedTojos().withOptimized();
        final Optimization optimization = this.optimization();
        final int total = new SumOf(
            new Threads<>(
                Runtime.getRuntime().availableProcessors(),
                new Mapped<>(
                    tojo -> () -> this.shaken(tojo, optimization),
                    new Filtered<>(
                        ForeignTojo::notShaken,
                        tojos
                    )
                )
            )
        ).intValue();
        if (total > 0) {
            Logger.info(
                this,
                "Shaken %d out of %d XMIR program(s) in %[ms]s",
                total, tojos.size(),
                System.currentTimeMillis() - start
            );
        } else {
            Logger.debug(this, "No XMIR programs out of %d shaken", tojos.size());
        }
    }

    /**
     * XMIR shaken to another XMIR.
     * @param tojo Foreign tojo
     * @param optimization Optimization to apply to XMIR
     * @return Amount of optimized XMIR files
     * @throws Exception If fails
     */
    private int shaken(final ForeignTojo tojo, final Optimization optimization) throws Exception {
        final Path source = tojo.optimized();
        final XML xmir = new XMLDocument(source);
        final String name = xmir.xpath("/program/@name").get(0);
        final Path base = this.targetDir.toPath().resolve(ShakeMojo.DIR);
        final Path target = new Place(name).make(base, AssembleMojo.XMIR);
        tojo.withShaken(
            new FpDefault(
                src -> optimization.apply(xmir).toString(),
                this.cache.toPath().resolve(ShakeMojo.CACHE),
                this.plugin.getVersion(),
                new TojoHash(tojo),
                base.relativize(target)
            ).apply(source, target)
        );
        return 1;
    }

    /**
     * Shake optimizations for tojos.
     *
     * @return Shake optimizations
     */
    private Optimization optimization() {
        final Optimization opt;
        if (this.trackOptimizationSteps) {
            opt = new OptSpy(this.targetDir.toPath().resolve(ShakeMojo.STEPS));
        } else {
            opt = new OptTrain();
        }
        return opt;
    }
}
