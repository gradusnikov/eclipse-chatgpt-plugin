package com.github.gradusnikov.eclipse.assistai.resources;

import java.util.List;

/**
 * What a caller wants a method's signature to become.
 * <p>
 * Every part is optional and a null part leaves that aspect of the signature alone, so
 * "make the return type long" is one field and nothing else. The parameter list is the
 * exception to describing a delta: when it is given it is the whole new list, in order,
 * and each entry says which current parameter it continues. A current parameter no
 * entry claims is removed, and an entry that claims none is added, with the expression
 * every existing call site should pass for it.
 *
 * @param parameters the complete new parameter list in order, or null to keep the
 *            current one
 * @param returnType the new return type as it would be written in source, or null
 * @param visibility {@code public}, {@code protected}, {@code package} or
 *            {@code private}, or null
 * @param newMethodName a new name for the method, or null
 * @param addExceptions fully qualified names of exception types to add to the throws
 *            clause; empty for none
 * @param removeExceptions names of exception types to remove from the throws clause;
 *            empty for none
 * @param keepOriginalAsDelegate whether to keep a method with the old signature that
 *            delegates to the new one, marked deprecated
 */
public record MethodSignatureChange(
    List<ParameterSpec> parameters,
    String returnType,
    String visibility,
    String newMethodName,
    List<String> addExceptions,
    List<String> removeExceptions,
    boolean keepOriginalAsDelegate
)
{
    /**
     * One entry of the new parameter list.
     *
     * @param name the parameter's name after the change
     * @param type its type after the change, as written in source; null keeps a
     *            continued parameter's type, and is an error for an added one
     * @param oldName the current parameter this one continues. When null the entry
     *            continues the current parameter that already has {@code name}, if
     *            there is one, and is otherwise added
     * @param defaultValue the expression existing call sites pass for an added
     *            parameter; ignored for a continued one
     */
    public record ParameterSpec( String name, String type, String oldName, String defaultValue )
    {
        /** Whether this entry claims a current parameter by its old name. */
        public String continuedName()
        {
            return oldName == null || oldName.isBlank() ? name : oldName;
        }
    }

    public MethodSignatureChange
    {
        addExceptions = addExceptions == null ? List.of() : List.copyOf( addExceptions );
        removeExceptions = removeExceptions == null ? List.of() : List.copyOf( removeExceptions );
        parameters = parameters == null ? null : List.copyOf( parameters );
    }

    /** Whether the change asks for anything at all. */
    public boolean isEmpty()
    {
        return parameters == null && isBlank( returnType ) && isBlank( visibility ) && isBlank( newMethodName )
                && addExceptions.isEmpty() && removeExceptions.isEmpty() && !keepOriginalAsDelegate;
    }

    private static boolean isBlank( String value )
    {
        return value == null || value.isBlank();
    }
}
