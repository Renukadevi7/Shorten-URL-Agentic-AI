import { runAgent } from "@/lib/agent/runtime";

export async function POST(request: Request) {
  let goal = "";
  try {
    const body = (await request.json()) as { goal?: string };
    goal = body.goal ?? "";
  } catch {
    return Response.json({ error: "Invalid JSON body." }, { status: 400 });
  }

  const encoder = new TextEncoder();
  const stream = new ReadableStream({
    async start(controller) {
      const send = (payload: unknown) => {
        controller.enqueue(encoder.encode(`data: ${JSON.stringify(payload)}\n\n`));
      };

      try {
        for await (const event of runAgent(goal)) {
          send(event);
          if (event.type === "error" || event.type === "done") break;
        }
      } catch (err) {
        send({
          type: "error",
          message: err instanceof Error ? err.message : "Agent run failed.",
        });
      } finally {
        controller.close();
      }
    },
  });

  return new Response(stream, {
    headers: {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
    },
  });
}
