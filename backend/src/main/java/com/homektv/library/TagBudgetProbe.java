package com.homektv.library;

import java.io.File;
import java.io.IOException;

@FunctionalInterface
interface TagBudgetProbe {

    TagBudget inspect(File file) throws IOException;
}

record TagBudget(boolean parserSafe) {

    static final TagBudget SAFE = new TagBudget(true);
    static final TagBudget UNSAFE = new TagBudget(false);
}
