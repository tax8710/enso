<script setup lang="ts">
import GraphEdge from '@/components/GraphEditor/GraphEdge.vue'
import type { GraphNavigator } from '@/providers/graphNavigator'
import { injectGraphSelection } from '@/providers/graphSelection'
import { injectInteractionHandler, type Interaction } from '@/providers/interactionHandler'
import type { PortId } from '@/providers/portInfo'
import { useGraphStore, type NodeId } from '@/stores/graph'
import { Ast } from '@/util/ast'
import { isAstId, type AstId } from '@/util/ast/abstract.ts'
import { Vec2 } from '@/util/data/vec2'
import { toast } from 'react-toastify'

const graph = useGraphStore()
const selection = injectGraphSelection(true)
const interaction = injectInteractionHandler()

const props = defineProps<{
  navigator: GraphNavigator
}>()

const emits = defineEmits<{
  createNodeFromEdge: [source: AstId, position: Vec2]
}>()

const editingEdge: Interaction = {
  cancel() {
    graph.clearUnconnected()
  },
  pointerdown(_e: PointerEvent, graphNavigator: GraphNavigator): boolean {
    if (graph.unconnectedEdge == null) return false
    let source: AstId | undefined
    let sourceNode: NodeId | undefined
    if (graph.unconnectedEdge.source) {
      source = graph.unconnectedEdge.source
      sourceNode = graph.db.getPatternExpressionNodeId(source)
    } else if (selection?.hoveredNode) {
      sourceNode = selection.hoveredNode
      source = graph.db.getNodeFirstOutputPort(sourceNode)
    }
    const target = graph.unconnectedEdge.target ?? selection?.hoveredPort
    const targetNode = target && graph.getPortNodeId(target)
    graph.transact(() => {
      if (source != null && sourceNode != targetNode) {
        if (target == null) {
          if (graph.unconnectedEdge?.disconnectedEdgeTarget != null)
            disconnectEdge(graph.unconnectedEdge.disconnectedEdgeTarget)
          emits('createNodeFromEdge', source, graphNavigator.sceneMousePos ?? Vec2.Zero)
        } else {
          createEdge(source, target)
        }
      } else if (source == null && target != null) {
        disconnectEdge(target)
      }
      graph.clearUnconnected()
    })
    return true
  },
}

interaction.setWhen(() => graph.unconnectedEdge != null, editingEdge)

function disconnectEdge(target: PortId) {
  graph.edit((edit) => {
    if (!graph.updatePortValue(edit, target, undefined)) {
      if (isAstId(target)) {
        console.warn(`Failed to disconnect edge from port ${target}, falling back to direct edit.`)
        edit.replaceValue(target, Ast.Wildcard.new(edit))
      } else {
        console.error(`Failed to disconnect edge from port ${target}, no fallback possible.`)
      }
    }
  })
}

function createEdge(source: AstId, target: PortId) {
  const ident = graph.db.getOutputPortIdentifier(source)
  if (ident == null) return

  const sourceNode = graph.db.getPatternExpressionNodeId(source)
  const targetNode = graph.getPortNodeId(target)
  if (sourceNode == null || targetNode == null) {
    return console.error(`Failed to connect edge, source or target node not found.`)
  }

  const edit = graph.startEdit()
  const reorderResult = graph.ensureCorrectNodeOrder(edit, sourceNode, targetNode)
  if (reorderResult === 'circular') {
    // Creating this edge would create a circular dependency. Prevent that and display error.
    toast.error('Could not connect due to circular dependency.')
  } else {
    const identAst = Ast.parse(ident, edit)
    if (!graph.updatePortValue(edit, target, identAst)) {
      if (isAstId(target)) {
        console.warn(`Failed to connect edge to port ${target}, falling back to direct edit.`)
        edit.replaceValue(target, identAst)
        graph.commitEdit(edit)
      } else {
        console.error(`Failed to connect edge to port ${target}, no fallback possible.`)
      }
    }
  }
}
</script>

<template>
  <div>
    <svg :viewBox="props.navigator.viewBox" class="overlay behindNodes">
      <GraphEdge v-for="edge in graph.connectedEdges" :key="edge.target" :edge="edge" />
    </svg>
    <svg
      v-if="graph.unconnectedEdge"
      :viewBox="props.navigator.viewBox"
      class="overlay aboveNodes nonInteractive"
    >
      <GraphEdge :edge="graph.unconnectedEdge" maskSource />
    </svg>
  </div>
</template>

<style scoped>
.overlay {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: none;
}

.overlay.behindNodes {
  z-index: -1;
}

.overlay.aboveNodes {
  z-index: 20;
}
</style>
