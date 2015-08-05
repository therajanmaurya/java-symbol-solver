package me.tomassetti.symbolsolver;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import jdk.nashorn.internal.ir.Symbol;
import me.tomassetti.symbolsolver.model.*;
import me.tomassetti.symbolsolver.model.declarations.MethodDeclaration;
import me.tomassetti.symbolsolver.model.declarations.ValueDeclaration;
import me.tomassetti.symbolsolver.model.declarations.TypeDeclaration;
import me.tomassetti.symbolsolver.model.usages.TypeUsageOfTypeDeclaration;
import me.tomassetti.symbolsolver.model.javaparser.JavaParserFactory;
import me.tomassetti.symbolsolver.model.javaparser.UnsolvedSymbolException;
import me.tomassetti.symbolsolver.model.javaparser.contexts.MethodCallExprContext;
import me.tomassetti.symbolsolver.model.javaparser.declarations.JavaParserSymbolDeclaration;
import me.tomassetti.symbolsolver.model.usages.TypeUsage;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Class to be used by final users to solve symbols for JavaParser ASTs.
 */
public class JavaParserFacade {

    private TypeSolver typeSolver;
    private SymbolSolver symbolSolver;

    public JavaParserFacade(TypeSolver typeSolver) {
        this.typeSolver = typeSolver;
        this.symbolSolver = new SymbolSolver(typeSolver);
    }

    public SymbolReference solve(NameExpr nameExpr) {
        return symbolSolver.solveSymbol(nameExpr.getName(), nameExpr);
    }

    public SymbolReference solve(Expression expr) {
        if (expr instanceof NameExpr) {
            return solve((NameExpr)expr);
        } else {
            throw new IllegalArgumentException(expr.getClass().getCanonicalName());
        }
    }

    /**
     * Given a method call find out to which method declaration it corresponds.
     */
    public SymbolReference<MethodDeclaration> solve(MethodCallExpr methodCallExpr) {
        List<TypeUsage> params = new LinkedList<>();
        for (Expression expression : methodCallExpr.getArgs()) {
            if (expression instanceof LambdaExpr) {
                params.add(new LambdaTypeUsagePlaceholder());
            } else {
                params.add(new JavaParserFacade(typeSolver).getType(expression));
            }
        }
        return JavaParserFactory.getContext(methodCallExpr).solveMethod(methodCallExpr.getName(), params, typeSolver);
    }

    /**
     * Should return more like a TypeApplication: a TypeDeclaration and possible parameters or array modifiers.
     * @return
     */
    public TypeUsage getType(Node node) {
        if (node instanceof NameExpr) {
            NameExpr nameExpr = (NameExpr) node;
            SymbolReference<? extends ValueDeclaration> ref = new SymbolSolver(typeSolver).solveSymbol(nameExpr.getName(), nameExpr);
            if (!ref.isSolved()) {
                throw new UnsolvedSymbolException(JavaParserFactory.getContext(nameExpr), nameExpr.getName());
            }
            return new TypeUsageOfTypeDeclaration(ref.getCorrespondingDeclaration().getType(typeSolver));
        } else if (node instanceof MethodCallExpr) {
            // first solve the method
            SymbolReference<MethodDeclaration> ref = new JavaParserFacade(typeSolver).solveMethod((MethodCallExpr)node);
            if (!ref.isSolved()) {
                throw new UnsolvedSymbolException(JavaParserFactory.getContext(node), ((MethodCallExpr)node).getName());
            }
            return new TypeUsageOfTypeDeclaration(ref.getCorrespondingDeclaration().getReturnType());
            // the type is the return type of the method
        } else if (node instanceof LambdaExpr) {
            if (node.getParentNode() instanceof MethodCallExpr) {
                MethodCallExpr callExpr = (MethodCallExpr) node.getParentNode();
                int pos = JavaParserSymbolDeclaration.getParamPos(node);
                SymbolReference<MethodDeclaration> refMethod = new JavaParserFacade(typeSolver).solve(callExpr);
                if (!refMethod.isSolved()) {
                    throw new UnsolvedSymbolException(null, callExpr.getName());
                }
                System.out.println("Method " + refMethod.getCorrespondingDeclaration().getName());
                System.out.println("Method param " + refMethod.getCorrespondingDeclaration().getParam(pos));
                return refMethod.getCorrespondingDeclaration().getParam(pos).getType(typeSolver).getUsage(node);
                //System.out.println("LAMBDA " + node.getParentNode());
                //System.out.println("LAMBDA CLASS " + node.getParentNode().getClass().getCanonicalName());
                //TypeUsage typeOfMethod = new JavaParserFacade(typeSolver).getType(node.getParentNode());
                //throw new UnsupportedOperationException("The type of a lambda expr depends on the position and its return value");
            } else {
                throw new UnsupportedOperationException("The type of a lambda expr depends on the position and its return value");
            }
        } else if (node instanceof VariableDeclarator) {
            if (node.getParentNode() instanceof FieldDeclaration) {
                FieldDeclaration parent = (FieldDeclaration) node.getParentNode();
                return new JavaParserFacade(typeSolver).convertToUsage(parent.getType(), parent);
            } else {
                throw new UnsupportedOperationException(node.getParentNode().getClass().getCanonicalName());
            }
        } else if (node instanceof FieldAccessExpr) {
            FieldAccessExpr fieldAccessExpr = (FieldAccessExpr) node;
            Optional<Value> value = new SymbolSolver(typeSolver).solveSymbolAsValue(fieldAccessExpr.getField(), fieldAccessExpr);
            if (value.isPresent()) {
                return value.get().getUsage();
            } else {
                throw new UnsolvedSymbolException(null, fieldAccessExpr.getField());
            }
        } else if (node instanceof ObjectCreationExpr) {
            ObjectCreationExpr objectCreationExpr = (ObjectCreationExpr)node;
            TypeUsage typeUsage = new JavaParserFacade(typeSolver).convertToUsage(objectCreationExpr.getType(), node);
            return typeUsage;
        } else {
            throw new UnsupportedOperationException(node.getClass().getCanonicalName());
        }
    }

    public TypeUsage convertToUsage(Type type, Node context) {
        return convertToUsage(type, JavaParserFactory.getContext(context));
    }

    public TypeUsage convertToUsage(Type type, Context context) {
        if (type instanceof ReferenceType) {
            ReferenceType referenceType = (ReferenceType) type;
            // TODO consider array modifiers
            return convertToUsage(referenceType.getType(), context);
        } else if (type instanceof ClassOrInterfaceType) {
            ClassOrInterfaceType classOrInterfaceType = (ClassOrInterfaceType)type;
            SymbolReference<TypeDeclaration> ref = context.solveType(classOrInterfaceType.getName(), typeSolver);
            if (!ref.isSolved()) {
                throw new UnsolvedSymbolException(null, classOrInterfaceType.getName());
            }
            TypeDeclaration typeDeclaration = ref.getCorrespondingDeclaration();
            List<TypeUsage> typeParameters = Collections.emptyList();
            if (classOrInterfaceType.getTypeArgs() != null) {
                typeParameters = classOrInterfaceType.getTypeArgs().stream().map((pt)->convertToUsage(pt, context)).collect(Collectors.toList());
            }
            return new TypeUsageOfTypeDeclaration(typeDeclaration, typeParameters);
        } else {
            throw new UnsupportedOperationException(type.getClass().getCanonicalName());
        }
    }

    private SymbolReference<MethodDeclaration> solveMethod(MethodCallExpr methodCallExpr) {
        List<TypeUsage> params = new ArrayList<>();
        if (methodCallExpr.getArgs() != null) {
            for (Expression param : methodCallExpr.getArgs()) {
                params.add(getType(param));
            }
        }
        return new MethodCallExprContext(methodCallExpr).solveMethod(methodCallExpr.getName(), params, typeSolver);
    }

    public TypeDeclaration convert(Type type, Node node) {
        return convert(type, JavaParserFactory.getContext(node));
    }

    public TypeDeclaration convert(Type type, Context context) {
        if (type instanceof ReferenceType) {
            ReferenceType referenceType = (ReferenceType) type;
            // TODO consider array modifiers
            return convert(referenceType.getType(), context);
        } else if (type instanceof ClassOrInterfaceType) {
            ClassOrInterfaceType classOrInterfaceType = (ClassOrInterfaceType)type;
            SymbolReference<TypeDeclaration> ref = context.solveType(classOrInterfaceType.getName(), typeSolver);
            if (!ref.isSolved()) {
                throw new UnsolvedSymbolException(null, classOrInterfaceType.getName());
            }
            return ref.getCorrespondingDeclaration();
        } else {
            throw new UnsupportedOperationException(type.getClass().getCanonicalName());
        }
    }
}
