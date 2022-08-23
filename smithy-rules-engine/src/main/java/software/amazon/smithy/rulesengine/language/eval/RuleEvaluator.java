/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.rulesengine.language.eval;

import java.util.List;
import java.util.Map;
import software.amazon.smithy.rulesengine.language.Endpoint;
import software.amazon.smithy.rulesengine.language.EndpointRuleset;
import software.amazon.smithy.rulesengine.language.lang.Identifier;
import software.amazon.smithy.rulesengine.language.lang.expr.Expr;
import software.amazon.smithy.rulesengine.language.lang.expr.Literal;
import software.amazon.smithy.rulesengine.language.lang.expr.Ref;
import software.amazon.smithy.rulesengine.language.lang.fn.BooleanEquals;
import software.amazon.smithy.rulesengine.language.lang.fn.Fn;
import software.amazon.smithy.rulesengine.language.lang.fn.GetAttr;
import software.amazon.smithy.rulesengine.language.lang.fn.IsSet;
import software.amazon.smithy.rulesengine.language.lang.fn.IsValidHostLabel;
import software.amazon.smithy.rulesengine.language.lang.fn.Not;
import software.amazon.smithy.rulesengine.language.lang.fn.ParseArn;
import software.amazon.smithy.rulesengine.language.lang.fn.ParseUrl;
import software.amazon.smithy.rulesengine.language.lang.fn.PartitionFn;
import software.amazon.smithy.rulesengine.language.lang.fn.StringEquals;
import software.amazon.smithy.rulesengine.language.lang.fn.Substring;
import software.amazon.smithy.rulesengine.language.lang.fn.UriEncode;
import software.amazon.smithy.rulesengine.language.lang.rule.Condition;
import software.amazon.smithy.rulesengine.language.lang.rule.Rule;
import software.amazon.smithy.rulesengine.language.visit.ExprVisitor;
import software.amazon.smithy.rulesengine.language.visit.FnVisitor;
import software.amazon.smithy.rulesengine.language.visit.RuleValueVisitor;
import software.amazon.smithy.utils.SmithyUnstableApi;

@SmithyUnstableApi
public class RuleEvaluator implements FnVisitor<Value>, ExprVisitor<Value> {
    private final Scope<Value> scope = new Scope<>();

    public Value evaluateRuleset(EndpointRuleset ruleset, Map<Identifier, Value> input) {
        return scope.inScope(
                () -> {
                    ruleset
                            .getParameters()
                            .toList()
                            .forEach(
                                    param -> {
                                        param.getDefault().ifPresent(value -> scope.insert(param.getName(), value));
                                    });
                    input.forEach(scope::insert);
                    for (Rule rule : ruleset.getRules()) {
                        Value result = handleRule(rule);
                        if (!result.isNone()) {
                            return result;
                        }
                    }
                    throw new RuntimeException("No rules in ruleset matched");
                });
    }

    @Override
    public Value visitLiteral(Literal literal) {
        return literal.eval(scope);
    }

    @Override
    public Value visitRef(Ref ref) {
        return scope
                .getValue(ref.getName())
                .orElseThrow(
                        () -> new RuntimeException(String.format("Invalid ruleset: %s was not in scope", ref)));
    }

    @Override
    public Value visitFn(Fn fn) {
        return fn.acceptFnVisitor(this);
    }

    @Override
    public Value visitPartition(PartitionFn fn) {
        return fn.eval(scope);
    }

    @Override
    public Value visitParseArn(ParseArn fn) {
        return fn.eval(scope);
    }

    @Override
    public Value visitIsValidHostLabel(IsValidHostLabel fn) {
        return fn.eval(scope);
    }

    @Override
    public Value visitBoolEquals(BooleanEquals fn) {
        return fn.eval(scope);
    }

    @Override
    public Value visitStringEquals(StringEquals fn) {
        return fn.eval(scope);
    }

    @Override
    public Value visitIsSet(IsSet fn) {
        return fn.eval(scope);
    }

    @Override
    public Value visitNot(Not not) {
        return Value.bool(!not.target().accept(this).expectBool());
    }

    @Override
    public Value visitGetAttr(GetAttr getAttr) {
        return getAttr.eval(scope);
    }

    @Override
    public Value visitParseUrl(ParseUrl parseUrl) {
        return parseUrl.eval(scope);
    }

    @Override
    public Value visitSubstring(Substring fn) {
        return fn.eval(scope);
    }

    @Override
    public Value visitUriEncode(UriEncode fn) {
        return fn.eval(scope);
    }

    private Value handleRule(Rule rule) {
        RuleEvaluator self = this;
        return scope.inScope(
                () -> {
                    for (Condition condition : rule.getConditions()) {
                        Value value = evaluateCondition(condition);
                        if (value.isNone() || value.equals(Value.bool(false))) {
                            return Value.none();
                        }
                    }
                    return rule.accept(new RuleValueVisitor<Value>() {
                        @Override
                        public Value visitTreeRule(List<Rule> rules) {
                            for (Rule subrule : rules) {
                                Value result = handleRule(subrule);
                                if (!result.isNone()) {
                                    return result;
                                }
                            }
                            throw new RuntimeException(
                                    String.format("no rules inside of tree rule matched—invalid rules (%s)", rule));
                        }

                        @Override
                        public Value visitErrorRule(Expr error) {
                            return error.accept(self);
                        }

                        @Override
                        public Value visitEndpointRule(Endpoint endpoint) {
                            return generateEndpoint(endpoint);
                        }
                    });
                });
    }

    public Value evaluateCondition(Condition condition) {
        Value value = condition.getFn().accept(this);
        if (!value.isNone()) {
            condition.getResult().ifPresent(res -> scope.insert(res, value));
        }
        return value;
    }

    public Value generateEndpoint(Endpoint endpoint) {
        Value.Endpoint.Builder builder =
                new Value.Endpoint.Builder(endpoint).url(endpoint.getUrl().accept(this).expectString());
        endpoint
                .getProperties()
                .forEach(
                        (key, value) -> {
                            builder.addProperty(key.toString(), value.accept(this));
                        });
        endpoint
                .getHeaders()
                .forEach(
                        (name, exprs) -> {
                            exprs.forEach(expr -> builder.addHeader(name, expr.accept(this).expectString()));
                        });

        return builder.build();
    }
}