/**
 * Copyright © 2010-2014 Nokia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jsonschema2pojo.rules;

import static java.util.Arrays.asList;
import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.containsOnly;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.join;
import static org.apache.commons.lang3.StringUtils.splitByCharacterTypeCamelCase;
import static org.apache.commons.lang3.StringUtils.upperCase;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Generated;

import org.jsonschema2pojo.Schema;
import org.jsonschema2pojo.SchemaMapper;
import org.jsonschema2pojo.exception.GenerationException;
import org.jsonschema2pojo.util.ParcelableHelper;
import org.jsonschema2pojo.util.SerializableHelper;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.codemodel.ClassType;
import com.sun.codemodel.JAnnotationUse;
import com.sun.codemodel.JBlock;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JClassContainer;
import com.sun.codemodel.JConditional;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JEnumConstant;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JForEach;
import com.sun.codemodel.JInvocation;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JPackage;
import com.sun.codemodel.JType;
import com.sun.codemodel.JVar;

/**
 * Applies the generation steps required for schemas of type "oneOf". Java
 * {@link java.lang.reflect.Proxy} won't work while generated classes have same
 * signature of methods. To implement "oneOf", a inner enum {@code ONE_OF} lists
 * all candidates of "oneOf" and capture the signature of all fields. And static
 * factory method {@code fromValue} will convert json string into one of the
 * option instance by matching their required and optional fields.
 * <p>
 * TODO: Parcel and parent schema are not supported.
 * </p>
 * <p>
 * Note: "required" value is not populated while generating @JsonProperty, this
 * need to be fixed to increase the accuracy while choosing which "oneOf" option
 * to use.
 * </p>
 * 
 * @see <a href=
 *      "http://json-schema.org/latest/json-schema-validation.html#anchor88">
 *      http://json-schema.org/latest/json-schema-validation.html#anchor88</a>
 * @author Chengwei.Yan
 */
public class OneOfRule implements Rule<JPackage, JType> {

	private static final String GETTER_NAME = "getValue";
	private static final String FACTORY_METHOD_NAME = "fromValue";
	private static final String ONE_OF = "oneOf";
	private static final String REF = "$ref";
	private static final String ONE_OF_ENUM_NAME = "ONE_OF";
	private static final String ONE_OF_VALUE_FIELD_NAME = "value";
	private static final String ENUM_CONSTANT_NAME = "value";
	private static final String CLASS_FIELD_NAME = "clazz";
	private static final String REQUIRED_FIELD_NAME = "requiredFields";
	private static final String OPTIONAL_FIELD_NAME = "optionalFields";
	/* Indices for anonymous class defined in "oneOf" body. */
	private static final Map<String, AtomicInteger> INDICES = new HashMap<String, AtomicInteger>();
	private final RuleFactory ruleFactory;

	/**
	 * Factory constructor.
	 */
	protected OneOfRule(RuleFactory ruleFactory,
			ParcelableHelper parcelableHelper) {
		this.ruleFactory = ruleFactory;
	}

	/**
	 * Applies this schema rule to take the required code generation steps.
	 * <p>
	 * When this rule is applied for schemas of type object, the properties of
	 * the schema are used to generate a new Java class and determine its
	 * characteristics. See other implementers of {@link Rule} for details.
	 * <p>
	 * A new Java type will be created when this rule is applied, it is
	 * annotated as {@link Generated}, it is given <code>equals</code>,
	 * <code>hashCode</code> and <code>toString</code> methods and implements
	 * {@link Serializable}.
	 */
	@Override
	public JType apply(String nodeName, JsonNode node, JPackage _package,
			Schema schema) {
		// Public modifier ...
		int modifier = _package.isPackage() ? JMod.PUBLIC : JMod.PUBLIC;
		JDefinedClass oneOf; // oneOf class
		JDefinedClass oneOfOptions; // enum of options
		try {
			oneOf = _package._class(modifier,
					getClassName(nodeName, node, _package), ClassType.CLASS);
			oneOfOptions = oneOf._class(modifier, ONE_OF_ENUM_NAME,
					ClassType.ENUM);
		} catch (JClassAlreadyExistsException e) {
			throw new GenerationException("Duplicate oneOf option '" + nodeName
					+ "'.");
		}

		schema.setJavaTypeIfEmpty(oneOf);
		addGeneratedAnnotation(oneOf);

		// Add enum options ...
		addOneOves(nodeName, node, _package, schema, oneOfOptions);

		// Add static creator factory method ...
		addFactoryMethod(oneOf, oneOfOptions);

		// Add constructor, creator and value getters ...
		addMethods(modifier, oneOf, oneOfOptions);

		if (ruleFactory.getGenerationConfig().isSerializable()) {
			SerializableHelper.addSerializableSupport(oneOf);
		}

		// hash and equal
		if (ruleFactory.getGenerationConfig().isIncludeHashcodeAndEquals()) {
			addHashCode(oneOf);
			addEquals(oneOf);
		}

		return oneOf;

	}

	/**
	 * Add parameterized constructor and value getter.
	 * 
	 * @param modifier
	 *            The modifier.
	 * @param oneOf
	 *            The generated oneOf class.
	 * @param oneOfOptions
	 *            The generated oneOf option enum.
	 */
	private void addMethods(int modifier, JDefinedClass oneOf,
			JDefinedClass oneOfOptions) {
		JClass jsonValue = oneOf.owner().ref(JsonValue.class);
		// Add enum field
		JFieldVar oneOfField = oneOf.field(JMod.PRIVATE | JMod.FINAL,
				oneOfOptions, ONE_OF);
		// Add value field
		JFieldVar valueField = oneOf.field(JMod.PRIVATE | JMod.FINAL, oneOf
				.owner().ref(Object.class), ONE_OF_VALUE_FIELD_NAME);

		// Constructor
		JMethod constructor = oneOf.constructor(modifier);

		JVar oneOfVar = constructor.param(oneOfOptions, ONE_OF);
		JVar valueVar = constructor
				.param(Object.class, ONE_OF_VALUE_FIELD_NAME);

		JBlock body = constructor.body();
		body.assign(JExpr._this().ref(oneOfField), oneOfVar);
		body.assign(JExpr._this().ref(valueField), valueVar);

		// Create getter ...
		createGetter(oneOf, jsonValue, valueField);
	}

	/**
	 * Create getter method.
	 * 
	 * @param oneOf
	 * @param jsonValue
	 * @param valueField
	 */
	private void createGetter(JDefinedClass oneOf, JClass jsonValue,
			JFieldVar valueField) {
		// Add getter ...
		JMethod getter = oneOf.method(JMod.PUBLIC, Object.class, GETTER_NAME);
		JBlock getterBody = getter.body();
		getterBody._return(JExpr._this().ref(valueField));
		getter.annotate(jsonValue);
	}

	/**
	 * Add "oneOf" option.
	 * 
	 * @param nodeName
	 *            The "oneOf" field name.
	 * @param node
	 *            The option node.
	 * @param _package
	 * @param schema
	 * @param oneOfOptions
	 *            The defined enum.
	 */
	private void addOneOves(final String nodeName, JsonNode node,
			JPackage _package, Schema schema, JDefinedClass oneOfOptions) {
		schema.setJavaTypeIfEmpty(oneOfOptions);
		JFieldVar enumFields = addEnumFields(oneOfOptions);
		addToString(oneOfOptions, enumFields);

		// List options ...
		ArrayNode oneOfs = (ArrayNode) node.path(ONE_OF);
		Iterator<JsonNode> it = oneOfs.elements();
		while (it.hasNext()) {
			final JsonNode optionNode = it.next();
			if (optionNode.has(REF)) {
				// Parsing reference ...
				JsonNode n = resolveRefs(optionNode, schema);
				if (isObject(n)) {
					String referType = optionNode.get(REF).asText();
					referType = referType
							.substring(referType.lastIndexOf('/') + 1);
					JType type = ruleFactory.getObjectRule().apply(referType,
							n, _package, schema);
					addOption(referType, type, oneOfOptions);
				} else {
					throw new GenerationException(
							"Only object type supported, '" + n
									+ "' cannot be used as an oneOf option.");
				}
			} else {
				// For anonymous object, create index ..
				AtomicInteger index = INDICES.get(nodeName);
				if (index == null) {
					INDICES.put(nodeName, index = new AtomicInteger(0));
				}

				// Create unique class name ...
				final String className = makeUnique(
						nodeName + index.incrementAndGet(), _package);
				// Convert ...
				JType type = ruleFactory.getObjectRule().apply(className,
						optionNode, _package, schema);
				addOption(className, type, oneOfOptions);
			}

		}
	}

	/**
	 * Obtains class name.
	 * 
	 * @param nodeName
	 * @param node
	 * @param _package
	 * @return
	 */
	private String getClassName(String nodeName, JsonNode node,
			JPackage _package) {
		String prefix = ruleFactory.getGenerationConfig().getClassNamePrefix();
		String suffix = ruleFactory.getGenerationConfig().getClassNameSuffix();
		String fieldName = ruleFactory.getNameHelper().getFieldName(nodeName,
				node);
		String capitalizedFieldName = capitalize(fieldName);
		String fullFieldName = createFullFieldName(capitalizedFieldName,
				prefix, suffix);

		String className = ruleFactory.getNameHelper()
				.replaceIllegalCharacters(fullFieldName);
		String normalizedName = ruleFactory.getNameHelper().normalizeName(
				className);
		return makeUnique(normalizedName, _package);
	}

	/**
	 * Create a full field name.
	 * 
	 * @param nodeName
	 * @param prefix
	 * @param suffix
	 * @return
	 */
	private String createFullFieldName(String nodeName, String prefix,
			String suffix) {
		String returnString = nodeName;
		if (prefix != null) {
			returnString = prefix + returnString;
		}

		if (suffix != null) {
			returnString = returnString + suffix;
		}

		return returnString;
	}

	/**
	 * Make class name unique.
	 * 
	 * @param className
	 * @param container
	 * @return a class name appends "_" if existed already.
	 */
	private String makeUnique(String className, JClassContainer container) {
		boolean found = false;
		Iterator<JDefinedClass> classes = container.classes();
		while (classes.hasNext()) {
			JDefinedClass aClass = classes.next();
			if (className.equalsIgnoreCase(aClass.name())) {
				found = true;
				break;
			}
		}
		if (found) {
			className = makeUnique(className + "_", container);
		}
		return className;
	}

	/**
	 * Resolve a node reference.
	 * 
	 * @param node
	 * @param parent
	 * @return a referred node.
	 */
	private JsonNode resolveRefs(JsonNode node, Schema parent) {
		if (node.has(REF)) {
			Schema refSchema = ruleFactory.getSchemaStore().create(parent,
					node.get(REF).asText());
			JsonNode refNode = refSchema.getContent();
			return resolveRefs(refNode, parent);
		} else {
			return node;
		}
	}

	/**
	 * Add fields for nested enum.
	 * 
	 * @param _enum
	 * @return
	 */
	private JFieldVar addEnumFields(JDefinedClass _enum) {
		// Enum constant name ...
		JFieldVar nameField = _enum.field(JMod.PRIVATE | JMod.FINAL,
				String.class, ENUM_CONSTANT_NAME);
		// Option class ...
		JFieldVar classField = _enum.field(JMod.PRIVATE | JMod.FINAL,
				Class.class, CLASS_FIELD_NAME);
		// Required fields ...
		JFieldVar requiredFields = _enum.field(JMod.PRIVATE | JMod.FINAL,
				String[].class, REQUIRED_FIELD_NAME);
		// Optional fields ...
		JFieldVar optinalFields = _enum.field(JMod.PRIVATE | JMod.FINAL,
				String[].class, OPTIONAL_FIELD_NAME);

		// private enum constructor.
		JMethod constructor = _enum.constructor(JMod.PRIVATE);
		JVar valueParam = constructor.param(_enum.owner().ref(String.class),
				ENUM_CONSTANT_NAME);
		JVar classParam = constructor.param(_enum.owner().ref(Class.class),
				CLASS_FIELD_NAME);

		JBlock body = constructor.body();
		body.assign(JExpr._this().ref(nameField), valueParam);
		body.assign(JExpr._this().ref(classField), classParam);

		/**
		 * FIXME: "required" annotation field was not generated by default.
		 */
		JVar requires = body.decl(
				_enum.owner().ref(List.class).narrow(String.class), "required");
		JVar optionals = body.decl(
				_enum.owner().ref(List.class).narrow(String.class), "optional");
		JClass fieldsType = _enum.owner().ref(ArrayList.class)
				.narrow(_enum.owner().ref(String.class));
		requires.init(JExpr._new(fieldsType));
		optionals.init(JExpr._new(fieldsType));

		// Populate field names ...
		JForEach forEach = body.forEach(_enum.owner().ref(Field.class), "f",
				classParam.invoke("getDeclaredFields"));
		JVar var = forEach.var();
		JInvocation anno = var.invoke("getAnnotation").arg(
				_enum.owner().ref(JsonProperty.class).dotclass());
		JConditional ifJsonProp = forEach.body()._if(anno.ne(JExpr._null()));
		JConditional ifRequired = ifJsonProp._then()._if(
				anno.invoke("required"));
		ifRequired._then().add(
				requires.invoke("add").arg(var.invoke("getName")));
		ifRequired._else().add(
				optionals.invoke("add").arg(var.invoke("getName")));

		_enum.owner().ref(Collections.class).staticInvoke("sort").arg(requires);
		_enum.owner().ref(Collections.class).staticInvoke("sort")
				.arg(optionals);
		body.assign(
				JExpr._this().ref(requiredFields),
				requires.invoke("toArray").arg(
						JExpr._new(_enum.owner().ref(String[].class))));
		body.assign(
				JExpr._this().ref(optinalFields),
				optionals.invoke("toArray").arg(
						JExpr._new(_enum.owner().ref(String[].class))));

		return nameField;
	}

	/**
	 * Add "toString()" for enum constant.
	 * 
	 * @param _enum
	 * @param valueField
	 */
	private void addToString(JDefinedClass _enum, JFieldVar valueField) {
		JMethod toString = _enum.method(JMod.PUBLIC, String.class, "toString");
		JBlock body = toString.body();

		body._return(JExpr._this().ref(valueField));

		ruleFactory.getAnnotator().enumValueMethod(toString);
		toString.annotate(Override.class);
	}

	/**
	 * Add an option.
	 * 
	 * @param name
	 * @param type
	 * @param oneOf
	 */
	private void addOption(String name, JType type, JDefinedClass oneOf) {
		if (type != null) {
			JEnumConstant option = oneOf.enumConstant(getConstantName(name));
			option.arg(JExpr.lit(name));
			option.arg(JExpr.dotclass(type.boxify()));
			ruleFactory.getAnnotator().enumConstant(option, name);
		}
	}

	/**
	 * Obtains a constant name.
	 * 
	 * @param name
	 * @return
	 */
	protected String getConstantName(String name) {
		List<String> enumNameGroups = new ArrayList<String>(
				asList(splitByCharacterTypeCamelCase(name)));

		String enumName = "";
		for (Iterator<String> iter = enumNameGroups.iterator(); iter.hasNext();) {
			if (containsOnly(ruleFactory.getNameHelper()
					.replaceIllegalCharacters(iter.next()), "_")) {
				iter.remove();
			}
		}

		enumName = upperCase(join(enumNameGroups, "_"));

		if (isEmpty(enumName)) {
			enumName = "__EMPTY__";
		} else if (Character.isDigit(enumName.charAt(0))) {
			enumName = "_" + enumName;
		}

		return enumName;
	}

	/**
	 * Add factory method.
	 * 
	 * @param parent
	 * @param _enum
	 */
	private void addFactoryMethod(JDefinedClass parent, JDefinedClass _enum) {
		JClass jsonCreator = parent.owner().ref(JsonCreator.class);
		JFieldVar quickLookupMap = addQuickLookupMap(parent, _enum);

		JMethod fromValue = parent.method(JMod.PUBLIC | JMod.STATIC, parent,
				FACTORY_METHOD_NAME);
		fromValue._throws(_enum.owner().ref(IOException.class));
		JClass paramType = parent
				.owner()
				.ref(HashMap.class)
				.narrow(parent.owner().ref(String.class),
						parent.owner().ref(Object.class));
		JVar valueParam = fromValue.param(paramType, "value");
		JBlock body = fromValue.body();

		// Print options found ...
		body.add(parent
				.owner()
				.ref(System.class)
				.staticRef("out")
				.invoke("println")
				.arg(JExpr.lit("Invoke oneOf factory method with ")
						.plus(quickLookupMap.invoke("size"))
						.plus(JExpr.lit(" options."))));
		body.add(parent.owner().ref(System.class).staticRef("out")
				.invoke("println").arg(valueParam));

		JClass stringListType = parent.owner().ref(ArrayList.class)
				.narrow(parent.owner().ref(String.class));
		JVar keys = body.decl(
				parent.owner().ref(List.class).narrow(String.class), "keys");
		keys.init(JExpr._new(stringListType).arg(valueParam.invoke("keySet")));

		JVar mapper = body.decl(parent.owner().ref(ObjectMapper.class),
				"mapper");
		mapper.init(JExpr._new(parent.owner().ref(ObjectMapper.class)));

		JForEach forEach = body.forEach(_enum, "c",
				JExpr.direct(_enum.fullName() + ".values()"));
		forEach.body().add(
				parent.owner()
						.ref(System.class)
						.staticRef("out")
						.invoke("println")
						.arg(parent
								.owner()
								.ref(String.class)
								.staticInvoke("format")
								.arg("Required: %s")
								.arg(parent
										.owner()
										.ref(Arrays.class)
										.staticInvoke("toString")
										.arg(forEach.var().ref(
												REQUIRED_FIELD_NAME)))));
		forEach.body().add(
				parent.owner()
						.ref(System.class)
						.staticRef("out")
						.invoke("println")
						.arg(parent
								.owner()
								.ref(String.class)
								.staticInvoke("format")
								.arg("Optional: %s")
								.arg(parent
										.owner()
										.ref(Arrays.class)
										.staticInvoke("toString")
										.arg(forEach.var().ref(
												OPTIONAL_FIELD_NAME)))));
		// Check fields ...

		JVar requires = forEach.body()
				.decl(parent.owner().ref(List.class).narrow(String.class),
						"requires");
		requires.init(JExpr._new(stringListType).arg(
				parent.owner().ref(Arrays.class).staticInvoke("asList")
						.arg(forEach.var().ref(REQUIRED_FIELD_NAME))));
		forEach.body().add(requires.invoke("removeAll").arg(keys));
		JConditional matchRequires = forEach.body()._if(
				requires.invoke("isEmpty"));
		JVar optionals = matchRequires._then().decl(
				parent.owner().ref(List.class).narrow(String.class),
				"optionals");
		optionals.init(JExpr._new(stringListType).arg(
				parent.owner().ref(Arrays.class).staticInvoke("asList")
						.arg(forEach.var().ref(OPTIONAL_FIELD_NAME))));
		JVar fields = matchRequires._then().decl(
				parent.owner().ref(List.class).narrow(String.class), "fields");
		fields.init(JExpr._new(stringListType).arg(keys));

		matchRequires._then().add(
				fields.invoke("removeAll").arg(
						parent.owner().ref(Arrays.class).staticInvoke("asList")
								.arg(forEach.var().ref(REQUIRED_FIELD_NAME))));
		matchRequires._then().add(fields.invoke("removeAll").arg(optionals));
		matchRequires._then().add(
				parent.owner()
						.ref(System.class)
						.staticRef("out")
						.invoke("println")
						.arg(parent
								.owner()
								.ref(String.class)
								.staticInvoke("format")
								.arg("Delta: %s")
								.arg(parent.owner().ref(Arrays.class)
										.staticInvoke("toString")
										.arg(fields.invoke("toArray")))));
		JConditional matchOptionals = matchRequires._then()._if(
				fields.invoke("isEmpty"));
		matchOptionals._then()._return(
				JExpr._new(parent)
						.arg(forEach.var())
						.arg(mapper
								.invoke("readValue")
								.arg(mapper.invoke("writeValueAsString").arg(
										valueParam))
								.arg(forEach.var().ref(CLASS_FIELD_NAME))));

		// JAnnotationUse annotate = fromValue.annotate(jsonCreator);
		// annotate.param("mode", Mode.DELEGATING);

		fromValue.annotate(jsonCreator);
		body._return(JExpr._null());
	}

	/**
	 * Add a quick lookup map of option to name.
	 * 
	 * @param parent
	 * @param _enum
	 * @return
	 */
	private JFieldVar addQuickLookupMap(JDefinedClass parent,
			JDefinedClass _enum) {

		JClass lookupType = _enum.owner().ref(Map.class)
				.narrow(_enum.owner().ref(String.class), _enum);
		JFieldVar lookupMap = parent.field(JMod.PRIVATE | JMod.STATIC
				| JMod.FINAL, lookupType, "CONSTANTS");

		JClass lookupImplType = _enum.owner().ref(HashMap.class)
				.narrow(_enum.owner().ref(String.class), _enum);
		lookupMap.init(JExpr._new(lookupImplType));

		JForEach forEach = parent.init().forEach(_enum, "c",
				JExpr.direct(_enum.fullName() + ".values()"));
		JInvocation put = forEach.body().invoke(lookupMap, "put");
		put.arg(forEach.var().ref("value"));
		put.arg(forEach.var());

		return lookupMap;
	}

	/**
	 * Is an object node?
	 * 
	 * @param node
	 * @return
	 */
	private boolean isObject(JsonNode node) {
		return node.path("type").asText().equals("object");
	}

	private void addGeneratedAnnotation(JDefinedClass jclass) {
		JAnnotationUse generated = jclass.annotate(Generated.class);
		generated.param("value", SchemaMapper.class.getPackage().getName());
	}

	/**
	 * Add hashcode method.
	 * 
	 * @param jclass
	 */
	private void addHashCode(JDefinedClass jclass) {
		Map<String, JFieldVar> fields = jclass.fields();
		if (fields.isEmpty()) {
			return;
		}

		JMethod hashCode = jclass.method(JMod.PUBLIC, int.class, "hashCode");

		Class<?> hashCodeBuilder = ruleFactory.getGenerationConfig()
				.isUseCommonsLang3() ? org.apache.commons.lang3.builder.HashCodeBuilder.class
				: org.apache.commons.lang.builder.HashCodeBuilder.class;

		JBlock body = hashCode.body();
		JClass hashCodeBuilderClass = jclass.owner().ref(hashCodeBuilder);
		JInvocation hashCodeBuilderInvocation = JExpr
				._new(hashCodeBuilderClass);

		if (!jclass._extends().name().equals("Object")) {
			hashCodeBuilderInvocation = hashCodeBuilderInvocation.invoke(
					"appendSuper").arg(JExpr._super().invoke("hashCode"));
		}

		for (JFieldVar fieldVar : fields.values()) {
			if ((fieldVar.mods().getValue() & JMod.STATIC) == JMod.STATIC)
				continue;
			hashCodeBuilderInvocation = hashCodeBuilderInvocation.invoke(
					"append").arg(fieldVar);
		}

		body._return(hashCodeBuilderInvocation.invoke("toHashCode"));

		hashCode.annotate(Override.class);
	}

	/**
	 * Add equals method.
	 * 
	 * @param jclass
	 */
	private void addEquals(JDefinedClass jclass) {
		Map<String, JFieldVar> fields = jclass.fields();
		if (fields.isEmpty()) {
			return;
		}

		JMethod equals = jclass.method(JMod.PUBLIC, boolean.class, "equals");
		JVar otherObject = equals.param(Object.class, "other");

		Class<?> equalsBuilder = ruleFactory.getGenerationConfig()
				.isUseCommonsLang3() ? org.apache.commons.lang3.builder.EqualsBuilder.class
				: org.apache.commons.lang.builder.EqualsBuilder.class;

		JBlock body = equals.body();

		body._if(otherObject.eq(JExpr._this()))._then()._return(JExpr.TRUE);
		body._if(otherObject._instanceof(jclass).eq(JExpr.FALSE))._then()
				._return(JExpr.FALSE);

		JVar rhsVar = body.decl(jclass, "rhs").init(
				JExpr.cast(jclass, otherObject));
		JClass equalsBuilderClass = jclass.owner().ref(equalsBuilder);
		JInvocation equalsBuilderInvocation = JExpr._new(equalsBuilderClass);

		if (!jclass._extends().name().equals("Object")) {
			equalsBuilderInvocation = equalsBuilderInvocation.invoke(
					"appendSuper").arg(
					JExpr._super().invoke("equals").arg(otherObject));
		}

		for (JFieldVar fieldVar : fields.values()) {
			if ((fieldVar.mods().getValue() & JMod.STATIC) == JMod.STATIC)
				continue;
			equalsBuilderInvocation = equalsBuilderInvocation.invoke("append")
					.arg(fieldVar).arg(rhsVar.ref(fieldVar.name()));
		}

		JInvocation reflectionEquals = jclass.owner().ref(equalsBuilder)
				.staticInvoke("reflectionEquals");
		reflectionEquals.arg(JExpr._this());
		reflectionEquals.arg(otherObject);

		body._return(equalsBuilderInvocation.invoke("isEquals"));

		equals.annotate(Override.class);
	}

}
