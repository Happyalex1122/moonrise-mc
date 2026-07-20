package org.moonrise.updater.safev1;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
    AtomicFileOpsTest.class,
    CompatibilityEvaluatorTest.class,
    UpdateManifestTest.class,
    UpdateWorkspaceTest.class
})
public class SafeUpdateTestSuite {
}
