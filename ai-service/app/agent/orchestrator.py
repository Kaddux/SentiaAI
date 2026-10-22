"""Agent loop with tool calling (plan Sections 10, 13).

Runs the OpenAI-compatible tool-calling loop: call the LLM, execute any
requested tools (with the JWT user scope), append the results, and repeat until
the model produces a final text answer (or hits the per-turn tool budget).
Persistence: the user turn and the final assistant answer are stored; the tool
round-trips are kept in this turn's message array only.
"""

from __future__ import annotations

import json
import logging

from sqlalchemy.ext.asyncio import AsyncSession

from app.agent.prompts import SYSTEM_PROMPT, context_block
from app.agent.tool_executor import execute_tool
from app.agent.tools import tools_for_provider
from app.memory.manager import load_history, save_message
from app.services.health_client import health_client
from app.services.llm_client import chat_with_fallback

log = logging.getLogger("sentiaai.agent")

MAX_TOOL_CALLS_PER_TURN = 5
MAX_TOOLS_PER_CALL = 3


async def run_assistant_turn(
    db: AsyncSession,
    user_id: str,
    jwt: str,
    conversation_id: int,
    user_text: str,
) -> tuple[str, list[str]]:
    """Process a user turn with tool calling; return (final_answer, tools_used)."""

    # Tier-1 context (profile + baseline); degrades to {} if backend is down.
    tier1 = await health_client.tier1_context(jwt)

    # Short-term memory (last N messages), oldest-first.
    history = await load_history(db, conversation_id)
    usable_history = [
        {"role": m["role"], "content": m["content"]}
        for m in history
        if m["role"] in ("user", "assistant", "tool")
    ]

    system = SYSTEM_PROMPT + context_block(tier1)
    messages: list[dict] = [{"role": "system", "content": system}]
    messages.extend(usable_history)
    messages.append({"role": "user", "content": user_text})

    # Persist the user turn once.
    await save_message(db, conversation_id, "user", user_text)

    tools = tools_for_provider()
    tool_calls_made = 0
    tools_used: list[str] = []
    final_answer = ""

    while tool_calls_made < MAX_TOOL_CALLS_PER_TURN:
        response = await chat_with_fallback(messages, tools)
        msg = response.choices[0].message

        if not getattr(msg, "tool_calls", None):
            final_answer = msg.content or ""
            break

        if len(msg.tool_calls) > MAX_TOOLS_PER_CALL:
            final_answer = "I have too many things to check at once. Let's take that one at a time."
            break

        # Append the assistant message carrying the tool calls.
        messages.append({
            "role": "assistant",
            "content": msg.content or "",
            "tool_calls": [
                {"id": tc.id, "type": "function",
                 "function": {"name": tc.function.name, "arguments": tc.function.arguments}}
                for tc in msg.tool_calls
            ],
        })

        # Execute each tool and append its result.
        for tc in msg.tool_calls:
            result = await execute_tool(tc.function.name, tc.function.arguments, jwt, user_id, db)
            tools_used.append(tc.function.name)
            messages.append({
                "role": "tool",
                "tool_call_id": tc.id,
                "content": json.dumps(result, default=str),
            })

        tool_calls_made += len(msg.tool_calls)

    if not final_answer:
        # Tool budget exhausted: ask for a final answer from the results already
        # gathered, without exposing more tools.
        try:
            response = await chat_with_fallback(messages)
            final_answer = (response.choices[0].message.content or "").strip()
        except Exception:
            final_answer = ""
    if not final_answer:
        final_answer = "I need a bit more specific information to help with that."

    await save_message(db, conversation_id, "assistant", final_answer)
    return final_answer, tools_used
