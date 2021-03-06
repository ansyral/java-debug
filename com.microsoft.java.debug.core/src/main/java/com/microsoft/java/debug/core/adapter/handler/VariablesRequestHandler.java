/*******************************************************************************
* Copyright (c) 2017 Microsoft Corporation and others.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Eclipse Public License v1.0
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v10.html
*
* Contributors:
*     Microsoft Corporation - initial API and implementation
*******************************************************************************/

package com.microsoft.java.debug.core.adapter.handler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.microsoft.java.debug.core.adapter.AdapterUtils;
import com.microsoft.java.debug.core.adapter.ErrorCode;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.microsoft.java.debug.core.adapter.IDebugRequestHandler;
import com.microsoft.java.debug.core.adapter.Messages.Response;
import com.microsoft.java.debug.core.adapter.Requests.Arguments;
import com.microsoft.java.debug.core.adapter.Requests.Command;
import com.microsoft.java.debug.core.adapter.Requests.VariablesArguments;
import com.microsoft.java.debug.core.adapter.Responses;
import com.microsoft.java.debug.core.adapter.Types;
import com.microsoft.java.debug.core.adapter.formatter.NumericFormatEnum;
import com.microsoft.java.debug.core.adapter.formatter.NumericFormatter;
import com.microsoft.java.debug.core.adapter.formatter.SimpleTypeFormatter;
import com.microsoft.java.debug.core.adapter.variables.IVariableFormatter;
import com.microsoft.java.debug.core.adapter.variables.Variable;
import com.microsoft.java.debug.core.adapter.variables.VariableProxy;
import com.microsoft.java.debug.core.adapter.variables.VariableUtils;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.Type;
import com.sun.jdi.Value;

public class VariablesRequestHandler implements IDebugRequestHandler {

    @Override
    public List<Command> getTargetCommands() {
        return Arrays.asList(Command.VARIABLES);
    }

    @Override
    public void handle(Command command, Arguments arguments, Response response, IDebugAdapterContext context) {
        IVariableFormatter variableFormatter = context.getVariableFormatter();
        VariablesArguments varArgs = (VariablesArguments) arguments;

        Map<String, Object> options = variableFormatter.getDefaultOptions();
        // This should be false by default(currently true for test).
        // User will need to explicitly turn it on by configuring launch.json
        boolean showStaticVariables = true;
        // TODO: When vscode protocol support customize settings of value format, showFullyQualifiedNames should be one of the options.
        boolean showFullyQualifiedNames = true;
        if (varArgs.format != null && varArgs.format.hex) {
            options.put(NumericFormatter.NUMERIC_FORMAT_OPTION, NumericFormatEnum.HEX);
        }
        if (showFullyQualifiedNames) {
            options.put(SimpleTypeFormatter.QUALIFIED_CLASS_NAME_OPTION, showFullyQualifiedNames);
        }

        List<Types.Variable> list = new ArrayList<>();
        Object container = context.getRecyclableIdPool().getObjectById(varArgs.variablesReference);
        // vscode will always send variables request to a staled scope, return the empty list is ok since the next
        // variable request will contain the right variablesReference.
        if (container == null) {
            response.body = new Responses.VariablesResponseBody(list);
            return;
        }

        if (!(container instanceof VariableProxy)) {
            AdapterUtils.setErrorResponse(response, ErrorCode.GET_VARIABLE_FAILURE,
                    String.format("VariablesRequest: Invalid variablesReference %d.", varArgs.variablesReference));
            return;
        }

        VariableProxy containerNode = (VariableProxy) container;
        List<Variable> childrenList;
        if (containerNode.getProxiedVariable() instanceof StackFrame) {
            try {
                StackFrame frame = (StackFrame) containerNode.getProxiedVariable();
                childrenList = VariableUtils.listLocalVariables(frame);
                Variable thisVariable = VariableUtils.getThisVariable(frame);
                if (thisVariable != null) {
                    childrenList.add(thisVariable);
                }
                if (showStaticVariables && frame.location().method().isStatic()) {
                    childrenList.addAll(VariableUtils.listStaticVariables(frame));
                }
            } catch (AbsentInformationException e) {
                AdapterUtils.setErrorResponse(response, ErrorCode.GET_VARIABLE_FAILURE,
                        String.format("Failed to get variables. Reason: %s", e.toString()));
                return;
            }
        } else {
            try {
                ObjectReference containerObj = (ObjectReference) containerNode.getProxiedVariable();

                if (varArgs.count > 0) {
                    childrenList = VariableUtils.listFieldVariables(containerObj, varArgs.start, varArgs.count);
                } else {
                    childrenList = VariableUtils.listFieldVariables(containerObj, showStaticVariables);
                }
            } catch (AbsentInformationException e) {
                AdapterUtils.setErrorResponse(response, ErrorCode.GET_VARIABLE_FAILURE,
                        String.format("Failed to get variables. Reason: %s", e.toString()));
                return;
            }
        }

        // Find variable name duplicates
        Set<String> duplicateNames = getDuplicateNames(childrenList.stream().map(var -> var.name)
                .collect(Collectors.toList()));
        Map<Variable, String> variableNameMap = new HashMap<>();
        if (!duplicateNames.isEmpty()) {
            Map<String, List<Variable>> duplicateVars =
                    childrenList.stream()
                            .filter(var -> duplicateNames.contains(var.name))
                            .collect(Collectors.groupingBy(var -> var.name, Collectors.toList()));

            duplicateVars.forEach((k, duplicateVariables) -> {
                Set<String> declarationTypeNames = new HashSet<>();
                boolean declarationTypeNameConflict = false;
                // try use type formatter to resolve name conflict
                for (Variable javaVariable : duplicateVariables) {
                    Type declarationType = javaVariable.getDeclaringType();
                    if (declarationType != null) {
                        String declarationTypeName = variableFormatter.typeToString(declarationType, options);
                        String compositeName = String.format("%s (%s)", javaVariable.name, declarationTypeName);
                        if (!declarationTypeNames.add(compositeName)) {
                            declarationTypeNameConflict = true;
                            break;
                        }
                        variableNameMap.put(javaVariable, compositeName);
                    }
                }
                // If there are duplicate names on declaration types, use fully qualified name
                if (declarationTypeNameConflict) {
                    for (Variable javaVariable : duplicateVariables) {
                        Type declarationType = javaVariable.getDeclaringType();
                        if (declarationType != null) {
                            variableNameMap.put(javaVariable, String.format("%s (%s)", javaVariable.name, declarationType.name()));
                        }
                    }
                }
            });
        }
        for (Variable javaVariable : childrenList) {
            Value value = javaVariable.value;
            String name = javaVariable.name;
            if (variableNameMap.containsKey(javaVariable)) {
                name = variableNameMap.get(javaVariable);
            }
            int referenceId = 0;
            if (value instanceof ObjectReference && VariableUtils.hasChildren(value, showStaticVariables)) {
                VariableProxy varProxy = new VariableProxy(containerNode.getThreadId(), containerNode.getScope(), value);
                referenceId = context.getRecyclableIdPool().addObject(containerNode.getThreadId(), varProxy);
            }
            Types.Variable typedVariables = new Types.Variable(name, variableFormatter.valueToString(value, options),
                    variableFormatter.typeToString(value == null ? null : value.type(), options), referenceId, null);
            if (javaVariable.value instanceof ArrayReference) {
                typedVariables.indexedVariables = ((ArrayReference) javaVariable.value).length();
            }
            list.add(typedVariables);
        }
        response.body = new Responses.VariablesResponseBody(list);
    }

    private Set<String> getDuplicateNames(Collection<String> list) {
        Set<String> result = new HashSet<>();
        Set<String> set = new HashSet<>();

        for (String item : list) {
            if (!set.contains(item)) {
                set.add(item);
            } else {
                result.add(item);
            }
        }
        return result;
    }
}
