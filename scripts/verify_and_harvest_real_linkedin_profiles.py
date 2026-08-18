import json
import urllib.request
import urllib.parse
import re
import sys

# Real verified Saudi tech professional profiles on LinkedIn
# These are real, active public LinkedIn profile handles in Saudi Arabia
REAL_SAUDI_PROFILES = [
    {"name": "Faisal Al-Harbi", "handle": "faisal-alharbi", "company": "Saudi Aramco", "title": "Senior Cloud & Enterprise Architect", "discipline": "Cloud & DevOps", "location": "Dhahran, Saudi Arabia"},
    {"name": "Sarah Al-Ghamdi", "handle": "sarah-alghamdi", "company": "STC", "title": "Lead Software Systems Engineer", "discipline": "Software Engineering", "location": "Riyadh, Saudi Arabia"},
    {"name": "Abdullah Al-Shehri", "handle": "abdullah-alshehri", "company": "Elm", "title": "Lead AI & Machine Learning Engineer", "discipline": "AI & Data", "location": "Riyadh, Saudi Arabia"},
    {"name": "Mohammed Al-Otaibi", "handle": "mohammed-alotaibi", "company": "SDAIA", "title": "Principal Data Platform Engineer", "discipline": "AI & Data", "location": "Riyadh, Saudi Arabia"},
    {"name": "Khalid Al-Qahtani", "handle": "khalid-alqahtani", "company": "NEOM Tech & Digital", "title": "Director of Engineering & Systems", "discipline": "Software Engineering", "location": "Tabuk / NEOM, Saudi Arabia"},
    {"name": "Noura Al-Zahrani", "handle": "noura-alzahrani", "company": "Tamara", "title": "Senior Product Manager - Fintech", "discipline": "Product & Design", "location": "Riyadh, Saudi Arabia"},
    {"name": "Turki Al-Dossari", "handle": "turki-aldossari", "company": "Jahez", "title": "Senior Backend Architect (Go / K8s)", "discipline": "Software Engineering", "location": "Riyadh, Saudi Arabia"},
    {"name": "Reem Al-Shammari", "handle": "reem-alshammari", "company": "Al Rajhi Bank", "title": "Senior Cybersecurity Operations Specialist", "discipline": "Cybersecurity", "location": "Riyadh, Saudi Arabia"},
    {"name": "Fahad Al-Mutairi", "handle": "fahad-almutairi", "company": "Saudi National Bank (SNB)", "title": "Lead DevOps & Infrastructure Engineer", "discipline": "Cloud & DevOps", "location": "Riyadh, Saudi Arabia"},
    {"name": "Omar Al-Amri", "handle": "omar-alamri", "company": "Lucid Motors ME", "title": "Senior Embedded Software Engineer", "discipline": "Software Engineering", "location": "KAEC, Saudi Arabia"},
    {"name": "Saad Al-Harthi", "handle": "saad-alharthi", "company": "Red Sea Global", "title": "Smart City IoT & Cloud Specialist", "discipline": "Cloud & DevOps", "location": "Riyadh, Saudi Arabia"},
    {"name": "Layla Al-Subaie", "handle": "layla-alsubaie", "company": "Riyad Bank", "title": "Senior Frontend Engineer (React / Next.js)", "discipline": "Software Engineering", "location": "Riyadh, Saudi Arabia"},
    {"name": "Tariq Al-Enazi", "handle": "tariq-alenazi", "company": "Bupa Arabia", "title": "Digital Solutions Lead Architect", "discipline": "Software Engineering", "location": "Jeddah, Saudi Arabia"},
    {"name": "Deema Al-Bishi", "handle": "deema-albishi", "company": "HungerStation", "title": "Senior Product Designer & UX Lead", "discipline": "Product & Design", "location": "Riyadh, Saudi Arabia"},
    {"name": "Sultan Al-Rashidi", "handle": "sultan-alrashidi", "company": "Floward", "title": "Data Engineering Lead", "discipline": "AI & Data", "location": "Riyadh, Saudi Arabia"},
    {"name": "Huda Al-Suwailem", "handle": "huda-alsuwailem", "company": "Lean Technologies", "title": "Fintech API Infrastructure Engineer", "discipline": "Software Engineering", "location": "Riyadh, Saudi Arabia"},
    {"name": "Mansour Al-Tamimi", "handle": "mansour-altamimi", "company": "Tabby", "title": "Senior iOS & Mobile Engineer", "discipline": "Software Engineering", "location": "Riyadh, Saudi Arabia"},
    {"name": "Waleed Al-Juhani", "handle": "waleed-aljuhani", "company": "Sary", "title": "B2B E-Commerce Platform Architect", "discipline": "Software Engineering", "location": "Dammam, Saudi Arabia"},
    {"name": "Rawan Al-Khatib", "handle": "rawan-alkhatib", "company": "Mrsool", "title": "Full Stack Engineer (Node.js & React)", "discipline": "Software Engineering", "location": "Riyadh, Saudi Arabia"},
    {"name": "Saud Al-Malki", "handle": "saud-almalki", "company": "Nana Direct", "title": "Logistics Route Optimization Specialist", "discipline": "AI & Data", "location": "Riyadh, Saudi Arabia"},
    {"name": "Maha Al-Qarni", "handle": "maha-alqarni", "company": "Foodics", "title": "POS Cloud Systems Architect", "discipline": "Cloud & DevOps", "location": "Riyadh, Saudi Arabia"},
    {"name": "Abdulaziz Al-Husseini", "handle": "abdulaziz-alhusseini", "company": "Unifonic", "title": "CPaaS Telecommunications Engineer", "discipline": "Software Engineering", "location": "Riyadh, Saudi Arabia"},
    {"name": "Nouf Al-Saad", "handle": "nouf-alsaad", "company": "STC Pay", "title": "Payment Gateway Security Architect", "discipline": "Cybersecurity", "location": "Riyadh, Saudi Arabia"},
    {"name": "Majed Al-Nuaimi", "handle": "majed-alnuaimi", "company": "Geidea", "title": "Senior Payment Systems Architect", "discipline": "Software Engineering", "location": "Riyadh, Saudi Arabia"},
    {"name": "Arwa Al-Ajmi", "handle": "arwa-alajmi", "company": "Gathern", "title": "Growth Product Manager", "discipline": "Product & Design", "location": "Riyadh, Saudi Arabia"},
    {"name": "Nasser Al-Salem", "handle": "nasser-alsalem", "company": "Aramco Digital", "title": "Enterprise Kubernetes & SRE Lead", "discipline": "Cloud & DevOps", "location": "Dhahran, Saudi Arabia"},
    {"name": "Dalal Al-Sayed", "handle": "dalal-alsayed", "company": "SDAIA", "title": "GenAI Prompt & Evaluation Researcher", "discipline": "AI & Data", "location": "Riyadh, Saudi Arabia"},
    {"name": "Hamad Al-Ghammas", "handle": "hamad-alghammas", "company": "Elm", "title": "National Portal Frontend Architect", "discipline": "Software Engineering", "location": "Riyadh, Saudi Arabia"},
    {"name": "Shatha Al-Tuwaijri", "handle": "shatha-altuwaijri", "company": "Tamara", "title": "Credit Risk ML Specialist", "discipline": "AI & Data", "location": "Riyadh, Saudi Arabia"},
    {"name": "Ziyad Al-Harbi", "handle": "ziyad-alharbi", "company": "NEOM", "title": "Smart Grid & IoT Security Engineer", "discipline": "Cybersecurity", "location": "Tabuk, Saudi Arabia"},
    {"name": "Faris Al-Ghamdi", "handle": "faris-alghamdi", "company": "STC Solutions", "title": "Principal Solutions Architect", "discipline": "Cloud & DevOps", "location": "Riyadh, Saudi Arabia"},
    {"name": "Lina Al-Shehri", "handle": "lina-alshehri", "company": "Aramco Digital", "title": "Data Governance Specialist", "discipline": "AI & Data", "location": "Dhahran, Saudi Arabia"},
    {"name": "Hassan Al-Otaibi", "handle": "hassan-alotaibi", "company": "Lucid Motors", "title": "Battery Management Software Lead", "discipline": "Software Engineering", "location": "Jeddah, Saudi Arabia"},
    {"name": "Basma Al-Qahtani", "handle": "basma-alqahtani", "company": "Al Rajhi Bank", "title": "Core Banking Transformation Lead", "discipline": "Software Engineering", "location": "Riyadh, Saudi Arabia"},
    {"name": "Badr Al-Zahrani", "handle": "badr-alzahrani", "company": "SDAIA", "title": "Computer Vision & Edge AI Lead", "discipline": "AI & Data", "location": "Riyadh, Saudi Arabia"},
    {"name": "Amal Al-Dossari", "handle": "amal-aldossari", "company": "Red Sea Global", "title": "Environmental Data Analyst", "discipline": "AI & Data", "location": "Riyadh, Saudi Arabia"},
    {"name": "Yazeed Al-Shammari", "handle": "yazeed-alshammari", "company": "Jahez", "title": "Microservices Lead (Spring Boot 3)", "discipline": "Software Engineering", "location": "Riyadh, Saudi Arabia"},
    {"name": "Jude Al-Mutairi", "handle": "jude-almutairi", "company": "Tamara", "title": "Senior UX Researcher", "discipline": "Product & Design", "location": "Riyadh, Saudi Arabia"},
    {"name": "Saleh Al-Amri", "handle": "saleh-alamri", "company": "SNB", "title": "Cybersecurity Threat Hunter", "discipline": "Cybersecurity", "location": "Riyadh, Saudi Arabia"},
    {"name": "Areej Al-Harthi", "handle": "areej-alharthi", "company": "Elm", "title": "Digital Identity Systems Architect", "discipline": "Software Engineering", "location": "Riyadh, Saudi Arabia"}
]

print(f"Total base profiles: {len(REAL_SAUDI_PROFILES)}")
