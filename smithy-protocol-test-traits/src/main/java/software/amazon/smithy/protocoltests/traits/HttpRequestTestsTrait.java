/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.protocoltests.traits;

import java.util.List;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.AbstractTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.utils.ListUtils;

/**
 * Defines HTTP request protocol tests.
 */
public final class HttpRequestTestsTrait extends AbstractTrait {
    public static final ShapeId ID = ShapeId.from("smithy.test#httpRequestTests");

    private final List<HttpRequestTestCase> testCases;

    public HttpRequestTestsTrait(List<HttpRequestTestCase> testCases) {
        this(SourceLocation.NONE, testCases);
    }

    public HttpRequestTestsTrait(SourceLocation sourceLocation, List<HttpRequestTestCase> testCases) {
        super(ID, sourceLocation);
        this.testCases = ListUtils.copyOf(testCases);
    }

    public static final class Provider extends AbstractTrait.Provider {
        public Provider() {
            super(ID);
        }

        @Override
        public Trait createTrait(ShapeId target, Node value) {
            ArrayNode values = value.expectArrayNode();
            List<HttpRequestTestCase> testCases = values.getElementsAs(HttpRequestTestCase::fromNode);
            return new HttpRequestTestsTrait(value.getSourceLocation(), testCases);
        }
    }

    public List<HttpRequestTestCase> getTestCases() {
        return testCases;
    }

    @Override
    protected Node createNode() {
        return getTestCases().stream().collect(ArrayNode.collect());
    }
}
