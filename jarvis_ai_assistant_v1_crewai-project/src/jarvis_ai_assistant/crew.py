import os
from crewai import LLM
from crewai import Agent, Crew, Process, Task
from crewai.project import CrewBase, agent, crew, task
from crewai_tools import (
	ScrapeWebsiteTool,
	FileReadTool,
	SerperDevTool
)

@CrewBase
class JarvisAiAssistantCrew:
    """JarvisAiAssistant crew"""

    @agent
    def chat_companion(self) -> Agent:
        return Agent(
            config=self.agents_config["chat_companion"],
            tools=[],
            reasoning=False,
            max_reasoning_attempts=None,
            inject_date=True,
            allow_delegation=False,
            max_iter=5,
            max_rpm=None,
            max_execution_time=60,
            llm=LLM(model="gemini/gemma-4-31b-it"),
        )

    @agent
    def personal_ai_assistant(self) -> Agent:
        return Agent(
            config=self.agents_config["personal_ai_assistant"],
            tools=[SerperDevTool()],
            reasoning=False,
            max_reasoning_attempts=None,
            inject_date=True,
            allow_delegation=False,
            max_iter=10,
            max_rpm=None,
            max_execution_time=120,
            llm=LLM(model="gemini/gemma-4-31b-it"),
        )

    @agent
    def communication_manager(self) -> Agent:
        return Agent(
            config=self.agents_config["communication_manager"],
            tools=[ScrapeWebsiteTool()],
            reasoning=False,
            max_reasoning_attempts=None,
            inject_date=True,
            allow_delegation=False,
            max_iter=10,
            max_rpm=None,
            max_execution_time=120,
            llm=LLM(model="gemini/gemma-4-31b-it"),
        )

    @agent
    def research_analyst(self) -> Agent:
        return Agent(
            config=self.agents_config["research_analyst"],
            tools=[FileReadTool(), SerperDevTool()],
            reasoning=False,
            max_reasoning_attempts=None,
            inject_date=True,
            allow_delegation=False,
            max_iter=10,
            max_rpm=None,
            max_execution_time=120,
            llm=LLM(model="gemini/gemma-4-31b-it"),
        )

    @agent
    def google_workspace_manager(self) -> Agent:
        return Agent(
            config=self.agents_config["google_workspace_manager"],
            tools=[],
            reasoning=False,
            max_reasoning_attempts=None,
            inject_date=True,
            allow_delegation=False,
            max_iter=10,
            max_rpm=None,
            max_execution_time=120,
            llm=LLM(model="gemini/gemma-4-31b-it"),
            apps=[
                "google_gmail/fetch_emails",
                "google_gmail/send_email",
                "google_gmail/create_draft",
                "google_gmail/get_message",
                "google_calendar/view_events",
                "google_calendar/create_event",
                "google_calendar/update_event",
                "google_calendar/get_availability",
                "google_docs/create_document_with_content",
                "google_docs/get_document",
                "google_docs/append_text",
                "google_sheets/create_spreadsheet",
                "google_sheets/get_values",
                "google_sheets/update_values",
            ],
        )

    @agent
    def morning_briefing_specialist(self) -> Agent:
        return Agent(
            config=self.agents_config["morning_briefing_specialist"],
            tools=[],
            reasoning=False,
            max_reasoning_attempts=None,
            inject_date=True,
            allow_delegation=False,
            max_iter=10,
            max_rpm=None,
            max_execution_time=120,
            llm=LLM(model="gemini/gemma-4-31b-it"),
            apps=[
                "google_gmail/fetch_emails",
                "google_calendar/view_events",
            ],
        )

    @agent
    def notification_sender(self) -> Agent:
        return Agent(
            config=self.agents_config["notification_sender"],
            tools=[],
            reasoning=False,
            max_reasoning_attempts=None,
            inject_date=True,
            allow_delegation=False,
            max_iter=10,
            max_rpm=None,
            max_execution_time=120,
            llm=LLM(model="gemini/gemma-4-31b-it"),
            apps=[
                "google_gmail/send_email",
            ],
        )

    @task
    def simple_conversation(self) -> Task:
        return Task(config=self.tasks_config["simple_conversation"], markdown=False)

    @task
    def analyze_request(self) -> Task:
        return Task(config=self.tasks_config["analyze_request"], markdown=False)

    @task
    def generate_morning_briefing(self) -> Task:
        return Task(config=self.tasks_config["generate_morning_briefing"], markdown=False)

    @task
    def conduct_research(self) -> Task:
        return Task(config=self.tasks_config["conduct_research"], markdown=False)

    @task
    def send_morning_briefing(self) -> Task:
        return Task(config=self.tasks_config["send_morning_briefing"], markdown=False)

    @task
    def manage_communications(self) -> Task:
        return Task(config=self.tasks_config["manage_communications"], markdown=False)

    @task
    def google_workspace_integration(self) -> Task:
        return Task(config=self.tasks_config["google_workspace_integration"], markdown=False)

    @crew
    def crew(self) -> Crew:
        """Creates the JarvisAiAssistantWithMorningBriefings crew"""
        manager_agent = Agent(
            role="Crew Manager",
            goal="Coordinate the team to achieve the objective efficiently",
            backstory="An experienced manager skilled in delegation and coordination",
            llm=LLM(model="gemini/gemma-4-31b-it"),
            allow_delegation=True,
            max_iter=10,
            max_execution_time=120,
        )

        return Crew(
            agents=self.agents,
            tasks=self.tasks,
            process=Process.hierarchical,
            verbose=True,
            manager_agent=manager_agent,
            memory=True,
            embedder={
                "provider": "huggingface",
                "config": {
                    "model": "sentence-transformers/all-MiniLM-L6-v2"
                }
            },
            chat_llm=LLM(model="gemini/gemma-4-31b-it"),
        )
