package eu.ggam.jsfparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import static java.util.stream.Collectors.toList;

/**
 *
 * @author Guillermo González de Agüero
 */
public class App {

    public static void main(String... args) throws IOException {
        String base = "target/classes/mojarra/";
        String dest = "src/main/java/";

        ParserConfiguration parserConfiguration = new ParserConfiguration();

        CombinedTypeSolver localCts = new CombinedTypeSolver();

        parserConfiguration.setSymbolResolver(new JavaSymbolSolver(localCts));
        JavaParser.setStaticConfiguration(parserConfiguration);

        Files.walk(Paths.get(base)).
                filter(Files::isRegularFile).
                filter(f -> f.toString().startsWith(base + "javax")).
                filter(f -> f.toString().endsWith(".java")).
                forEach((Path f) -> {
                    if (f.toString().endsWith("package-info.java")) {
                        return;
                    }

                    try (InputStream newInputStream = Files.newInputStream(f)) {
                        CompilationUnit cu = JavaParser.parse(newInputStream);
                        boolean changeMethods = changeMethods(cu);
                        //System.out.println("Change methods: " + changeMethods + " " + f);
                        if (changeMethods) {
                            Path newPath = Paths.get(f.toString().replace(base, dest));
                            Files.createDirectories(newPath.getParent());
                            try (OutputStream os = Files.newOutputStream(newPath)) {
                                os.write(cu.toString().getBytes());
                            }
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    private static boolean changeMethods(CompilationUnit cu) {
        cu.accept(new ModifierVisitor<ClassOrInterfaceDeclaration>() {
            @Override
            public Node visit(ImportDeclaration n, ClassOrInterfaceDeclaration arg) {
                String nameAsString = n.getNameAsString();

                if (nameAsString.startsWith("com.sun.")
                        || nameAsString.startsWith("javax.faces.validator.MultiFieldValidationUtils")
                        || nameAsString.startsWith("javax.faces.ServletContextFacesContextFactory")
                        || nameAsString.startsWith("javax.faces.validator.MessageFactory")
                        || nameAsString.startsWith("javax.faces.application.SharedUtils")) {
                    return null;
                }

                return super.visit(n, arg);
            }

            @Override
            public Visitable visit(ClassOrInterfaceDeclaration type, ClassOrInterfaceDeclaration arg) {
                if (!type.getModifiers().contains(Modifier.PUBLIC) && !type.getModifiers().contains(Modifier.PROTECTED)) {
                    return null;
                }

                if (type.isInterface()) {
                    // Ignore interface methods
                    return super.visit(type, arg);
                }

                List<BodyDeclaration<?>> membersToRemove = new ArrayList<>();
                NodeList<BodyDeclaration<?>> members = type.getMembers();
                for (BodyDeclaration<?> member : members) {
                    if (shouldRemove(member)) {
                        membersToRemove.add(member);
                        continue;
                    }

                    if (member instanceof MethodDeclaration) {
                        MethodDeclaration method = (MethodDeclaration) member;
                        if (!method.isAbstract()) {
                            method.setBody(getBlock());
                        }
                    }

                    if (member instanceof ConstructorDeclaration) {
                        ConstructorDeclaration method = (ConstructorDeclaration) member;
                        if (!method.isAbstract()) {
                            BlockStmt exceptionBlock = getBlock();

                            NodeList<Statement> statements = method.getBody().getStatements();
                            if (!statements.isEmpty() && statements.get(0) instanceof ExplicitConstructorInvocationStmt) {
                                // There's a super() or this() call. Check if we can change it to default constructor
                                if (!type.getDefaultConstructor().isPresent() || type.getDefaultConstructor().get().equals(method)) {
                                    ExplicitConstructorInvocationStmt explicitConstructor = (ExplicitConstructorInvocationStmt) statements.get(0);

                                    NodeList<Expression> arguments = explicitConstructor.getArguments();

                                    NodeList<Expression> newArguments = new NodeList<>(arguments.stream().
                                            //map(Expression::asNameExpr).
                                            //map(NameExpr::resolve).
                                            map(e -> {
                                                if(!e.isNameExpr()) {
                                                    return "null";
                                                }
                                                
                                                try {
                                                    e.asNameExpr().resolve().getType();
                                                } catch (UnsolvedSymbolException ex) {
                                                    // Will always fail since no SymbolResolver has been configured.
                                                    // The exception contains the *unqualified* name of the class
                                                    return "(" + ex.getName() + ") null";
                                                }
                                                throw new RuntimeException("This cannot be reached");
                                            }).
                                            map(n -> new NameExpr(n)).
                                            collect(toList()));

                                    explicitConstructor.setArguments(newArguments);

                                    NodeList<Statement> newStatements = new NodeList<>();
                                    newStatements.add(explicitConstructor);
                                    newStatements.addAll(exceptionBlock.getStatements());

                                    exceptionBlock = new BlockStmt(newStatements);
                                }
                            }

                            method.setBody(exceptionBlock);
                        }
                    }
                }
                type.getMembers().removeAll(membersToRemove);

                return super.visit(type, arg);
            }

        }, null);

        return !cu.getTypes().isEmpty();
    }

    private static boolean shouldRemove(BodyDeclaration member) {
        EnumSet<Modifier> modifiers;
        if (member instanceof FieldDeclaration) {
            modifiers = ((FieldDeclaration) member).getModifiers();

            // Keep private static variables. They might be used somewhere
            if (!modifiers.contains(Modifier.PUBLIC) && !modifiers.contains(Modifier.PROTECTED) && !modifiers.contains(Modifier.STATIC)) {
                return true;
            }
        } else if (member instanceof MethodDeclaration) {
            modifiers = ((MethodDeclaration) member).getModifiers();
            if (!modifiers.contains(Modifier.PUBLIC) && !modifiers.contains(Modifier.PROTECTED)) {
                return true;
            }
        } else if (member instanceof ConstructorDeclaration) {
            modifiers = ((CallableDeclaration) member).getModifiers();

            // Keep private constructors. They might be used somewhere
            if (!modifiers.contains(Modifier.PUBLIC) && !modifiers.contains(Modifier.PROTECTED) && !modifiers.contains(Modifier.PRIVATE)) {
                return true;
            }
        }

        return false;
    }

    private static BlockStmt getBlock() {
        BlockStmt block = new BlockStmt();
        NodeList nodeList = new NodeList();
        nodeList.add(new StringLiteralExpr("This is API for compile only purposes."));
        ThrowStmt throwStmt = new ThrowStmt(new ObjectCreationExpr(null, JavaParser.parseClassOrInterfaceType(UnsupportedOperationException.class
                .getName()), nodeList));
        block.addStatement(throwStmt);
        return block;
    }

}
