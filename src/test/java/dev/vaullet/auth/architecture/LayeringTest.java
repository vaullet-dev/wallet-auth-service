package dev.vaullet.auth.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import dev.vaullet.common.test.ArchitectureRules;

/**
 * The layering rules, enforced by the build instead of by code review.
 *
 * <p>Architecture documented only in a README decays: the first pull request that injects a
 * repository into a controller because it was quicker will be approved by someone in a hurry, and
 * after three of those the layers exist in name only. These tests fail that pull request instead.
 *
 * <p>The rules live in {@code backend-common-test} and are shared with every other service, so this
 * file is the lines that point them at this codebase. A rule that is copied is a rule that gets
 * edited locally, and a boundary each service defines slightly differently is not a boundary.
 *
 * <p>Note that {@code ..api..} still matches after the move to {@code api.v1}: ArchUnit treats it as
 * "a package with {@code api} as a segment", so the rules keep holding across API versions without
 * a change here — which is one more argument for versioning by package.
 */
@AnalyzeClasses(packages = LayeringTest.BASE, importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringTest {

    static final String BASE = "dev.vaullet.auth";

    @ArchTest
    static final ArchRule layers_are_respected = ArchitectureRules.layersAreRespected(BASE);

    @ArchTest
    static final ArchRule controllers_do_not_reach_into_repositories =
            ArchitectureRules.controllersDoNotReachIntoRepositories();

    /**
     * The template's rule is "JPA entities stay in the DAO layer", which cannot apply here: this
     * service uses JDBC, so there are no entities to keep in. The equivalent boundary is that nothing
     * outside {@code dao} touches {@code JdbcTemplate} — worth enforcing for the same reason, since a
     * stray query in a service method is a statement that escaped review.
     */
    @ArchTest
    static final ArchRule jdbc_stays_in_the_dao_layer =
            ArchitectureRules.persistenceStaysInTheDaoLayer(BASE, "org.springframework.jdbc..");

    /**
     * The one that pays for itself at step 2: just-in-time provisioning drives {@code create} from a
     * Kafka listener, which has no request to bind. A service that reached for {@code HttpStatus}
     * would have to be rewritten to be reachable from there.
     */
    @ArchTest
    static final ArchRule the_service_layer_knows_nothing_about_http =
            ArchitectureRules.serviceLayerKnowsNothingAboutHttp(BASE);

    @ArchTest
    static final ArchRule no_field_injection = ArchitectureRules.noFieldInjection();

    @ArchTest
    static final ArchRule no_standard_streams = ArchitectureRules.noStandardStreams();

    @ArchTest
    static final ArchRule no_java_util_logging = ArchitectureRules.noJavaUtilLogging();

    @ArchTest
    static final ArchRule no_joda_or_legacy_date_api = ArchitectureRules.noLegacyDateApi();
}
