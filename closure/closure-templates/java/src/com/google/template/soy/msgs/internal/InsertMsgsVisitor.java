/*
 * Copyright 2008 Google Inc.
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

package com.google.template.soy.msgs.internal;

import com.google.common.collect.Lists;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.msgs.SoyMsgBundle;
import com.google.template.soy.msgs.restricted.SoyMsg;
import com.google.template.soy.msgs.restricted.SoyMsgPart;
import com.google.template.soy.msgs.restricted.SoyMsgPlaceholderPart;
import com.google.template.soy.msgs.restricted.SoyMsgRawTextPart;
import com.google.template.soy.soytree.AbstractSoyNodeVisitor;
import com.google.template.soy.soytree.MsgFallbackGroupNode;
import com.google.template.soy.soytree.MsgHtmlTagNode;
import com.google.template.soy.soytree.MsgNode;
import com.google.template.soy.soytree.MsgPlaceholderNode;
import com.google.template.soy.soytree.MsgPluralNode;
import com.google.template.soy.soytree.MsgSelectNode;
import com.google.template.soy.soytree.RawTextNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyNode;
import com.google.template.soy.soytree.SoyNode.BlockNode;
import com.google.template.soy.soytree.SoyNode.ParentSoyNode;
import com.google.template.soy.soytree.SoyNode.StandaloneNode;

import java.util.List;

import javax.annotation.Nullable;


/**
 * Visitor for inserting translated messages into Soy tree. This pass replaces the
 * MsgFallbackGroupNodes in the tree with sequences of RawTextNodes and other nodes. The only
 * exception is plural/select messages. This pass currently does not replace MsgFallbackGroupNodes
 * that contain plural/select messages.
 *
 * <p> Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 * <p> If the Soy tree doesn't contain plural/select messages, then after this pass, the Soy tree
 * should no longer contain MsgFallbackGroupNodes, MsgNodes, MsgPlaceholderNodes, or
 * MsgHtmlTagNodes. If the Soy tree contains plural/select messages, then the only messages left in
 * the tree after this pass runs should be the plural/select messages.
 *
 * <p> Note that the Soy tree is usually simplifiable after this pass is run (e.g. it usually
 * contains consecutive RawTextNodes). It's usually advisable to run a simplification pass after
 * this pass.
 *
 * @author Kai Huang
 */
public class InsertMsgsVisitor extends AbstractSoyNodeVisitor<Void> {


  /**
   * Exception thrown when a plural or select message is encountered.
   */
  public static class EncounteredPlrselMsgException extends RuntimeException {

    public final MsgNode msgNode;

    public EncounteredPlrselMsgException(MsgNode msgNode) {
      this.msgNode = msgNode;
    }
  }


  /** The bundle of translated messages, or null to use the messages from the Soy source. */
  private final SoyMsgBundle msgBundle;

  /** If set to true, then don't report an error when encountering plural or select messages. */
  private final boolean dontErrorOnPlrselMsgs;

  /** The node id generator for the parse tree. Retrieved from the root SoyFileSetNode. */
  private IdGenerator nodeIdGen;

  /** The replacement nodes for the current MsgFallbackGroupNode we're visiting (during a pass). */
  private List<StandaloneNode> currReplacementNodes;


  /**
   * @param msgBundle The bundle of translated messages, or null to use the messages from the Soy
   *     source.
   * @param dontErrorOnPlrselMsgs If set to true, then this pass won't report an error when
   *     encountering a plural or select message. Instead, plural and select messages will simply
   *     not be replaced ({@code MsgNode} left in the tree as-is). If set to false, then this pass
   *     will throw an {@link EncounteredPlrselMsgException} when encountering a plural or
   *     select message.
   */
  public InsertMsgsVisitor(@Nullable SoyMsgBundle msgBundle, boolean dontErrorOnPlrselMsgs) {
    this.msgBundle = msgBundle;
    this.dontErrorOnPlrselMsgs = dontErrorOnPlrselMsgs;
  }


  @Override public Void exec(SoyNode node) {

    // Retrieve the node id generator from the root of the parse tree.
    nodeIdGen = node.getNearestAncestor(SoyFileSetNode.class).getNodeIdGenerator();

    // Execute the pass.
    super.exec(node);

    return null;
  }


  // -----------------------------------------------------------------------------------------------
  // Implementations for specific nodes.


  @Override protected void visitMsgFallbackGroupNode(MsgFallbackGroupNode node) {

    // Check for plural or select message. Either report error or don't replace.
    for (MsgNode msg : node.getChildren()) {
      if (msg.numChildren() == 1 &&
          (msg.getChild(0) instanceof MsgSelectNode || msg.getChild(0) instanceof MsgPluralNode)) {
        if (dontErrorOnPlrselMsgs) {
          return;
        } else {
          throw new EncounteredPlrselMsgException(msg);
        }
      }
    }

    // Figure out which message we're going to use, and build its list of replacement nodes.
    currReplacementNodes = null;
    if (msgBundle != null) {
      for (MsgNode msg : node.getChildren()) {
        SoyMsg translation = msgBundle.getMsg(MsgUtils.computeMsgIdForDualFormat(msg));
        if (translation != null) {
          buildReplacementNodesFromTranslation(msg, translation);
          break;
        }
      }
    }
    if (currReplacementNodes == null) {
      buildReplacementNodesFromSource(node.getChild(0));
    }

    // Replace this MsgFallbackGroupNode with the replacement nodes.
    BlockNode parent = node.getParent();
    int indexInParent = parent.getChildIndex(node);
    parent.removeChild(indexInParent);
    parent.addChildren(indexInParent, currReplacementNodes);
    currReplacementNodes = null;
  }


  /**
   * Private helper for visitMsgFallbackGroupNode() to build the list of replacement nodes for a
   * message from its translation.
   */
  private void buildReplacementNodesFromTranslation(MsgNode msg, SoyMsg translation) {

    currReplacementNodes = Lists.newArrayList();

    for (SoyMsgPart msgPart : translation.getParts()) {

      if (msgPart instanceof SoyMsgRawTextPart) {
        // Append a new RawTextNode to the currReplacementNodes list.
        String rawText = ((SoyMsgRawTextPart) msgPart).getRawText();
        currReplacementNodes.add(new RawTextNode(nodeIdGen.genId(), rawText));

      } else if (msgPart instanceof SoyMsgPlaceholderPart) {
        // Get the representative placeholder node and iterate through its contents.
        String placeholderName = ((SoyMsgPlaceholderPart) msgPart).getPlaceholderName();
        MsgPlaceholderNode placeholderNode = msg.getRepPlaceholderNode(placeholderName);
        for (StandaloneNode contentNode : placeholderNode.getChildren()) {
          // If the content node is a MsgHtmlTagNode, it needs to be replaced by a number of
          // consecutive siblings. This is done by visiting the MsgHtmlTagNode. Otherwise, we
          // simply add the content node to the currReplacementNodes list being built.
          if (contentNode instanceof MsgHtmlTagNode) {
            visit(contentNode);
          } else {
            currReplacementNodes.add(contentNode);
          }
        }

      } else {
        throw new AssertionError();
      }
    }
  }


  /**
   * Private helper for visitMsgFallbackGroupNode() to build the list of replacement nodes for a
   * message from its source.
   */
  private void buildReplacementNodesFromSource(MsgNode msg) {

    currReplacementNodes = Lists.newArrayList();

    for (StandaloneNode child : msg.getChildren()) {

      if (child instanceof RawTextNode) {
        currReplacementNodes.add(child);

      } else if (child instanceof MsgPlaceholderNode) {
        for (StandaloneNode contentNode : ((MsgPlaceholderNode) child).getChildren()) {
          // If the content node is a MsgHtmlTagNode, it needs to be replaced by a number of
          // consecutive siblings. This is done by visiting the MsgHtmlTagNode. Otherwise, we
          // simply add the content node to the currReplacementNodes list being built.
          if (contentNode instanceof MsgHtmlTagNode) {
            visit(contentNode);
          } else {
            currReplacementNodes.add(contentNode);
          }
        }

      } else {
        throw new AssertionError();
      }
    }
  }


  @Override protected void visitMsgHtmlTagNode(MsgHtmlTagNode node) {

    for (StandaloneNode child : node.getChildren()) {
      currReplacementNodes.add(child);
    }
  }


  // -----------------------------------------------------------------------------------------------
  // Fallback implementation.


  @Override protected void visitSoyNode(SoyNode node) {
    if ((node instanceof ParentSoyNode<?>)) {
      visitChildrenAllowingConcurrentModification((ParentSoyNode<?>) node);
    }
  }

}
