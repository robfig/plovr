/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.soytree;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.template.soy.base.SoySyntaxException;
import com.google.template.soy.base.internal.BaseUtils;
import com.google.template.soy.basetree.SyntaxVersion;
import com.google.template.soy.basetree.SyntaxVersionBound;
import com.google.template.soy.data.SanitizedContent.ContentKind;
import com.google.template.soy.soytree.TemplateNode.SoyFileHeaderInfo;
import com.google.template.soy.soytree.defn.HeaderParam;
import com.google.template.soy.soytree.defn.SoyDocParam;
import com.google.template.soy.soytree.defn.TemplateParam;
import com.google.template.soy.types.SoyType;
import com.google.template.soy.types.SoyTypeRegistry;
import com.google.template.soy.types.aggregate.UnionType;
import com.google.template.soy.types.parse.ParseException;
import com.google.template.soy.types.parse.TypeParser;
import com.google.template.soy.types.primitive.NullType;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;


/**
 * Builder for TemplateNode.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 * @author Kai Huang
 */
public abstract class TemplateNodeBuilder {


  /**
   * Value class used in the input to method {@link #setHeaderDecls}.
   *
   * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
   */
  public static class DeclInfo {

    /** The command name of the decl tag. */
    public final String cmdName;
    /** The command text of the decl tag. */
    public final String cmdText;
    /** The SoyDoc string associated with the decl, or null if none. */
    @Nullable public final String soyDoc;

    public DeclInfo(String cmdName, String cmdText, String soyDoc) {
      this.cmdName = cmdName;
      this.cmdText = cmdText;
      this.soyDoc = soyDoc;
    }
  }


  /** Info from the containing Soy file's header declarations. */
  protected final SoyFileHeaderInfo soyFileHeaderInfo;

  /** The registry of named types. */
  private final SoyTypeRegistry typeRegistry;


  /** The id for this node. */
  protected Integer id;

  /** The lowest known syntax version bound. Value may be adjusted multiple times. */
  @Nullable protected SyntaxVersionBound syntaxVersionBound;

  /** The command text. */
  protected String cmdText;

  /** This template's name.
   *  This is private instead of protected to enforce use of setTemplateNames(). */
  private String templateName;

  /** This template's partial name. Only applicable for V2.
   *  This is private instead of protected to enforce use of setTemplateNames(). */
  private String partialTemplateName;

  /** A string suitable for display in user msgs as the template name. */
  protected String templateNameForUserMsgs;

  /** Whether this template is private. */
  protected Boolean isPrivate;

  /** The mode of autoescaping for this template.
   *  This is private instead of protected to enforce use of setAutoescapeInfo(). */
  private AutoescapeMode autoescapeMode;

  /** Required CSS namespaces. */
  private ImmutableList<String> requiredCssNamespaces;

  /** Strict mode context. Nonnull iff autoescapeMode is strict.
   *  This is private instead of protected to enforce use of setAutoescapeInfo(). */
  private ContentKind contentKind;

  /** Whether setSoyDoc() has been called. */
  protected boolean isSoyDocSet;

  /** The full SoyDoc, including the start/end tokens, or null. */
  protected String soyDoc;

  /** The description portion of the SoyDoc (before declarations), or null. */
  protected String soyDocDesc;

  /** The params from template header and/or SoyDoc. Null if no decls and no SoyDoc. */
  @Nullable protected ImmutableList<TemplateParam> params;


  /**
   * @param soyFileHeaderInfo Info from the containing Soy file's header declarations.
   * @param typeRegistry Type registry used in parsing type declarations.
   */
  protected TemplateNodeBuilder(
      SoyFileHeaderInfo soyFileHeaderInfo, @Nullable SoyTypeRegistry typeRegistry) {
    this.soyFileHeaderInfo = soyFileHeaderInfo;
    this.typeRegistry = typeRegistry;
    this.syntaxVersionBound = null;
    this.isSoyDocSet = false;
    // All other fields default to null.
  }


  /**
   * Sets the id for the node to be built.
   * @return This builder.
   */
  public TemplateNodeBuilder setId(int id) {
    Preconditions.checkState(this.id == null);
    this.id = id;
    return this;
  }


  /**
   * Sets the command text for the node to be built. The command text will be parsed to fill in
   * fields such as templateName and autoescapeMode.
   * @return This builder.
   */
  public abstract TemplateNodeBuilder setCmdText(String cmdText);


  /**
   * Returns a template name suitable for display in user msgs.
   *
   * <p>Note: This public getter exists because this info is needed by SoyFileParser for error
   * reporting before the TemplateNode is ready to be built.
   */
  public String getTemplateNameForUserMsgs() {
    Preconditions.checkState(templateNameForUserMsgs != null);
    return templateNameForUserMsgs;
  }


  /**
   * Sets the SoyDoc for the node to be built. The SoyDoc will be parsed to fill in SoyDoc param
   * info.
   * @return This builder.
   */
  public TemplateNodeBuilder setSoyDoc(String soyDoc) {

    Preconditions.checkState(! isSoyDocSet);
    Preconditions.checkState(cmdText != null);  // not strictly necessary

    this.isSoyDocSet = true;
    this.soyDoc = soyDoc;

    if (soyDoc != null) {
      Preconditions.checkArgument(soyDoc.startsWith("/**") && soyDoc.endsWith("*/"));
      String cleanedSoyDoc = cleanSoyDocHelper(soyDoc);
      this.soyDocDesc = parseSoyDocDescHelper(cleanedSoyDoc);
      SoyDocDeclsInfo soyDocDeclsInfo = parseSoyDocDeclsHelper(cleanedSoyDoc);
      this.addParams(soyDocDeclsInfo.params);
      if (soyDocDeclsInfo.lowestSyntaxVersionBound != null) {
        SyntaxVersionBound newSyntaxVersionBound = new SyntaxVersionBound(
            soyDocDeclsInfo.lowestSyntaxVersionBound,
            "Template SoyDoc has incorrect param declarations where the param name is not a valid" +
                " identifier: " + soyDocDeclsInfo.incorrectSoyDocParamSrcs);
        this.syntaxVersionBound =
            SyntaxVersionBound.selectLower(this.syntaxVersionBound, newSyntaxVersionBound);
      }

    } else {
      SyntaxVersionBound newSyntaxVersionBound = new SyntaxVersionBound(
          SyntaxVersion.V2_0, "Template is missing SoyDoc.");
      this.syntaxVersionBound =
          SyntaxVersionBound.selectLower(this.syntaxVersionBound, newSyntaxVersionBound);
      this.soyDocDesc = null;
      // Note: Don't set this.params to null here because params can also come from header decls.
    }

    return this;
  }


  /**
   * Sets the template header decls.
   * @param declInfos DeclInfo objects for the decls found in the template header.
   * @return This builder.
   */
  public TemplateNodeBuilder setHeaderDecls(List<DeclInfo> declInfos) {

    List<TemplateParam> params = Lists.newArrayList();

    for (DeclInfo declInfo : declInfos) {

      if (declInfo.cmdName.equals("@param") || declInfo.cmdName.equals("@param?")) {
        Matcher cmdTextMatcher = HEADER_PARAM_DECL_CMD_TEXT_PATTERN.matcher(declInfo.cmdText);
        if (! cmdTextMatcher.matches()) {
          throw SoySyntaxException.createWithoutMetaInfo(
              "Invalid @param declaration command text \"" + declInfo.cmdText + "\".");
        }
        String key = cmdTextMatcher.group(1);
        if (! BaseUtils.isIdentifier(key)) {
          throw SoySyntaxException.createWithoutMetaInfo(
              "Invalid @param key '" + key + "' (must be an identifier).");
        }
        String typeSrc = cmdTextMatcher.group(2);
        SoyType type;
        boolean isRequired = true;
        try {
          Preconditions.checkNotNull(typeRegistry);
          type = new TypeParser(typeSrc, typeRegistry).parseTypeDeclaration();
          if (declInfo.cmdName.equals("@param?")) {
            isRequired = false;
            type = typeRegistry.getOrCreateUnionType(type, NullType.getInstance());
          } else if (type instanceof UnionType && ((UnionType) type).isNullable()) {
            isRequired = false;
          }
        } catch (ParseException e) {
          throw SoySyntaxException.createWithoutMetaInfo(e.getMessage());
        }
        params.add(new HeaderParam(key, typeSrc, type, isRequired, declInfo.soyDoc));

      } else {
        // The parser should never send us an illegal decl name.
        throw new AssertionError();
      }
    }

    this.addParams(params);

    return this;
  }


  /**
   * Helper for {@code setSoyDoc()} and {@code setHeaderDecls()}. This method is intended to be
   * called at most once for SoyDoc params and at most once for header params.
   * @param params The params to add.
   */
  private void addParams(Collection<? extends TemplateParam> params) {

    if (this.params == null) {
      this.params = ImmutableList.copyOf(params);
    } else {
      this.params = ImmutableList.<TemplateParam>builder()
          .addAll(this.params)
          .addAll(params)
          .build();
    }

    // Check params.
    Set<String> seenParamKeys = Sets.newHashSet();
    for (TemplateParam param : this.params) {
      if (param.name().equals("ij")) {
        throw SoySyntaxException.createWithoutMetaInfo(
            "Invalid param name 'ij' ('ij' is for injected data ref).");
      }
      if (seenParamKeys.contains(param.name())) {
        throw SoySyntaxException.createWithoutMetaInfo(
            "Duplicate declaration of param '" + param.name() + "'.");
      }
      seenParamKeys.add(param.name());
    }
  }


  /**
   * Builds the template node. Will error if not enough info as been set on this builder.
   */
  public abstract TemplateNode build();


  // -----------------------------------------------------------------------------------------------
  // Protected helpers for fields that need extra logic when being set.


  protected void setAutoescapeInfo(
      AutoescapeMode autoescapeMode, @Nullable ContentKind contentKind) {

    Preconditions.checkArgument(autoescapeMode != null);
    this.autoescapeMode = autoescapeMode;

    if (contentKind == null && autoescapeMode == AutoescapeMode.STRICT) {
      // Default mode is HTML.
      contentKind = ContentKind.HTML;
    } else if (contentKind != null && autoescapeMode != AutoescapeMode.STRICT) {
      // TODO: Perhaps this could imply strict escaping?
      throw SoySyntaxException.createWithoutMetaInfo(
          "kind=\"...\" attribute is only valid with autoescape=\"strict\".");
    }
    this.contentKind = contentKind;
  }


  protected AutoescapeMode getAutoescapeMode() {
    Preconditions.checkState(autoescapeMode != null);
    return autoescapeMode;
  }


  @Nullable protected ContentKind getContentKind() {
    return contentKind;
  }


  protected void setRequiredCssNamespaces(ImmutableList<String> requiredCssNamespaces) {
    this.requiredCssNamespaces = Preconditions.checkNotNull(requiredCssNamespaces);
  }


  protected ImmutableList<String> getRequiredCssNamespaces() {
    return Preconditions.checkNotNull(requiredCssNamespaces);
  }


  protected void setTemplateNames(String templateName, @Nullable String partialTemplateName) {

    this.templateName = templateName;
    this.partialTemplateName = partialTemplateName;

    if (partialTemplateName != null) {
      if (! BaseUtils.isIdentifierWithLeadingDot(partialTemplateName)) {
        throw SoySyntaxException.createWithoutMetaInfo(
            "Invalid template name \"" + partialTemplateName + "\".");
      }
    } else {
      if (! BaseUtils.isDottedIdentifier(templateName)) {
        throw SoySyntaxException.createWithoutMetaInfo(
            "Invalid template name \"" + templateName + "\".");
      }
    }
  }


  protected String getTemplateName() {
    Preconditions.checkState(templateName != null);
    return templateName;
  }


  @Nullable protected String getPartialTemplateName() {
    return partialTemplateName;
  }


  // -----------------------------------------------------------------------------------------------
  // Private static helpers for parsing template header declarations.


  /** Pattern for the command text in a header param decl. */
  // Note: group 1 = key, group 2 = type.
  private static final Pattern HEADER_PARAM_DECL_CMD_TEXT_PATTERN =
      Pattern.compile("^ ([^:\\s]+) \\s* : \\s* (\\S .*) $", Pattern.COMMENTS | Pattern.DOTALL);


  // -----------------------------------------------------------------------------------------------
  // Private static helpers for parsing template SoyDoc.


  /** Pattern for a newline. */
  private static final Pattern NEWLINE = Pattern.compile("\\n|\\r\\n?");

  /** Pattern for a SoyDoc start token, including spaces up to the first newline.*/
  private static final Pattern SOY_DOC_START =
      Pattern.compile("^ [/][*][*] [\\ ]* \\r?\\n?", Pattern.COMMENTS);

  /** Pattern for a SoyDoc end token, including preceding spaces up to the last newline.*/
  private static final Pattern SOY_DOC_END =
      Pattern.compile("\\r?\\n? [\\ ]* [*][/] $", Pattern.COMMENTS);

  /** Pattern for a SoyDoc declaration. */
  // group(1) = declaration keyword; group(2) = declaration text.
  private static final Pattern SOY_DOC_DECL_PATTERN =
      Pattern.compile("( @param[?]? ) \\s+ ( \\S+ )", Pattern.COMMENTS);

  /** Pattern for SoyDoc parameter declaration text. */
  private static final Pattern SOY_DOC_PARAM_TEXT_PATTERN =
      Pattern.compile("[a-zA-Z_]\\w*", Pattern.COMMENTS);


  /**
   * Private helper for the constructor to clean the SoyDoc.
   * (1) Changes all newlines to "\n".
   * (2) Escapes deprecated javadoc tags.
   * (3) Strips the start/end tokens and spaces (including newlines if they occupy their own lines).
   * (4) Removes common indent from all lines (e.g. space-star-space).
   *
   * @param soyDoc The SoyDoc to clean.
   * @return The cleaned SoyDoc.
   */
  private static String cleanSoyDocHelper(String soyDoc) {

    // Change all newlines to "\n".
    soyDoc = NEWLINE.matcher(soyDoc).replaceAll("\n");

    // Escape all @deprecated javadoc tags.
    // TODO(user): add this to the specification and then also generate @Deprecated annotations
    soyDoc = soyDoc.replace("@deprecated", "&#64;deprecated");

    // Strip start/end tokens and spaces (including newlines if they occupy their own lines).
    soyDoc = SOY_DOC_START.matcher(soyDoc).replaceFirst("");
    soyDoc = SOY_DOC_END.matcher(soyDoc).replaceFirst("");

    // Split into lines.
    List<String> lines = Lists.newArrayList(Splitter.on(NEWLINE).split(soyDoc));

    // Remove indent common to all lines. Note that SoyDoc indents often include a star
    // (specifically the most common indent is space-star-space). Thus, we first remove common
    // spaces, then remove one common star, and finally, if we did remove a star, then we once again
    // remove common spaces.
    removeCommonStartCharHelper(lines, ' ', true);
    if (removeCommonStartCharHelper(lines, '*', false) == 1) {
      removeCommonStartCharHelper(lines, ' ', true);
    }

    return Joiner.on('\n').join(lines);
  }


  /**
   * Private helper for {@code cleanSoyDocHelper()}.
   * Removes a common character at the start of all lines, either once or as many times as possible.
   *
   * <p> Special case: Empty lines count as if they do have the common character for the purpose of
   * deciding whether all lines have the common character.
   *
   * @param lines The list of lines. If removal happens, then the list elements will be modified.
   * @param charToRemove The char to remove from the start of all lines.
   * @param shouldRemoveMultiple Whether to remove the char as many times as possible.
   * @return The number of chars removed from the start of each line.
   */
  private static int removeCommonStartCharHelper(
      List<String> lines, char charToRemove, boolean shouldRemoveMultiple) {

    int numCharsToRemove = 0;

    // Count num chars to remove.
    boolean isStillCounting = true;
    do {
      boolean areAllLinesEmpty = true;
      for (String line : lines) {
        if (line.length() == 0) {
          continue;  // empty lines are okay
        }
        areAllLinesEmpty = false;
        if (line.length() <= numCharsToRemove ||
            line.charAt(numCharsToRemove) != charToRemove) {
          isStillCounting = false;
          break;
        }
      }
      if (areAllLinesEmpty) {
        isStillCounting = false;
      }
      if (isStillCounting) {
        numCharsToRemove += 1;
      }
    } while (isStillCounting && shouldRemoveMultiple);

    // Perform the removal.
    if (numCharsToRemove > 0) {
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        if (line.length() == 0) {
          continue;  // don't change empty lines
        }
        lines.set(i, line.substring(numCharsToRemove));
      }
    }

    return numCharsToRemove;
  }


  /**
   * Private helper for the constructor to parse the SoyDoc description.
   *
   * @param cleanedSoyDoc The cleaned SoyDoc text. Must not be null.
   * @return The description (with trailing whitespace removed).
   */
  private static String parseSoyDocDescHelper(String cleanedSoyDoc) {

    Matcher paramMatcher = SOY_DOC_DECL_PATTERN.matcher(cleanedSoyDoc);
    int endOfDescPos = (paramMatcher.find()) ? paramMatcher.start() : cleanedSoyDoc.length();
    String soyDocDesc = cleanedSoyDoc.substring(0, endOfDescPos);
    return CharMatcher.WHITESPACE.trimTrailingFrom(soyDocDesc);
  }


  /**
   * Return value for {@code parseSoyDocDeclsHelper()}.
   */
  private static class SoyDocDeclsInfo {

    /** The params successfully parsed from the SoyDoc. */
    public List<SoyDocParam> params;
    /** SoyDoc param decl source strings with incorrect syntax. */
    public List<String> incorrectSoyDocParamSrcs;
    /** Lowest syntax version bound (exclusive) for incorrect SoyDoc param decls. */
    public SyntaxVersion lowestSyntaxVersionBound;

    public SoyDocDeclsInfo() {
      this.params = Lists.newArrayList();
      this.incorrectSoyDocParamSrcs = Lists.newArrayListWithCapacity(0);
      this.lowestSyntaxVersionBound = null;
    }
  }


  /**
   * Private helper for the constructor to parse the SoyDoc declarations.
   *
   * @param cleanedSoyDoc The cleaned SoyDoc text. Must not be null.
   * @return A SoyDocDeclsInfo object with the parsed info.
   */
  private static SoyDocDeclsInfo parseSoyDocDeclsHelper(String cleanedSoyDoc) {

    SoyDocDeclsInfo result = new SoyDocDeclsInfo();

    Matcher matcher = SOY_DOC_DECL_PATTERN.matcher(cleanedSoyDoc);
    // Important: This statement finds the param for the first iteration of the loop.
    boolean isFound = matcher.find();
    while (isFound) {

      // Save the match groups.
      String declKeyword = matcher.group(1);
      String declText = matcher.group(2);

      // Find the next declaration in the SoyDoc and extract this declaration's desc string.
      int descStart = matcher.end();
      // Important: This statement finds the param for the next iteration of the loop.
      // We must find the next param now in order to know where the current param's desc ends.
      isFound = matcher.find();
      int descEnd = (isFound) ? matcher.start() : cleanedSoyDoc.length();
      String desc = cleanedSoyDoc.substring(descStart, descEnd).trim();

      if (declKeyword.equals("@param") || declKeyword.equals("@param?")) {

        if (SOY_DOC_PARAM_TEXT_PATTERN.matcher(declText).matches()) {
          result.params.add(new SoyDocParam(declText, declKeyword.equals("@param"), desc));

        } else {
          result.incorrectSoyDocParamSrcs.add(declKeyword + " " + declText);
          if (declText.startsWith("{")) {
            // In V1.0, allow incorrect syntax where '{' is the start of the declText.
            if (result.lowestSyntaxVersionBound == null) {
              result.lowestSyntaxVersionBound = SyntaxVersion.V2_0;
            }
          } else {
            result.lowestSyntaxVersionBound = SyntaxVersion.V1_0;
          }
        }

      } else {
        throw new AssertionError();
      }
    }

    return result;
  }

}
